package app.moneytracker.data.io

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.data.db.DefaultNames
import app.moneytracker.data.prefs.SettingsRepositoryImpl
import app.moneytracker.data.rates.RatesApi
import app.moneytracker.data.rates.RemoteRatesDataSource
import app.moneytracker.data.repository.BudgetRepositoryImpl
import app.moneytracker.data.repository.CategoryRepositoryImpl
import app.moneytracker.data.repository.ExchangeRateRepositoryImpl
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.data.repository.RecurringRepositoryImpl
import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.BackupFormatException
import app.moneytracker.domain.repository.CsvErrorReason
import app.moneytracker.domain.repository.CsvRowError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class DataTransferTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Europe/Belgrade")
    private val clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), zone)
    private val rates = RateSnapshot(BigDecimal("117.1523"), BigDecimal("95.4321"), LocalDate.of(2026, 10, 1))
    private val scopes = mutableListOf<CoroutineScope>()
    private val dbs = mutableListOf<AppDatabase>()
    private var n = 0

    private inner class Env(fileDb: Boolean = false) {
        val db: AppDatabase = (
            if (fileDb) Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            else Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            ).addCallback(DatabaseSeeder(context)).allowMainThreadQueries().build().also { dbs += it }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val settings = SettingsRepositoryImpl(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "test-${n++}-${System.nanoTime()}.preferences_pb") },
        )
        private val offlineApi = object : RatesApi {
            override suspend fun get(url: String): JsonObject = throw IOException("offline")
        }
        val rateRepo = ExchangeRateRepositoryImpl(db.exchangeRateDao(), RemoteRatesDataSource(offlineApi), clock)
        val expenses = ExpenseRepositoryImpl(db.expenseDao())
        val categories = CategoryRepositoryImpl(db.categoryDao())
        val budgets = BudgetRepositoryImpl(db.budgetDao())
        val recurring = RecurringRepositoryImpl(db, db.recurringDao())
        val transfer = DataTransferImpl(context, db, settings, rateRepo, DefaultNames(context), Json { ignoreUnknownKeys = true }, clock)

        suspend fun add(amount: Money, category: Long, at: String, tags: List<String> = emptyList(), comment: String? = null, withRates: Boolean = true) =
            expenses.insert(
                Expense.create(amount, category, 1, Instant.parse(at), zone, if (withRates) rates else null, clock.instant(), comment, tags),
            )

        fun exportCsv() = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { transfer.exportCsv(it) } }.toByteArray()
        fun exportJson() = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { transfer.exportJson(it) } }.toByteArray()
    }

    @After
    fun tearDown() {
        dbs.forEach { if (it.isOpen) it.close() }
        scopes.forEach { it.cancel() }
        context.deleteDatabase(AppDatabase.NAME)
    }

    @Test
    fun csvRoundTripAndReimportIsIdempotent() = runTest {
        val source = Env()
        val cafe = source.categories.upsert(Category(name = "Кафе", iconKey = "local_cafe", color = 0, parentId = 1))
        source.add(Money(125050, CurrencyCode.RSD), cafe, "2026-10-14T11:45:00Z", listOf("работа", "кофе"), "Латте, \"большой\"")
        source.add(Money(1999, CurrencyCode.EUR), 3, "2026-10-01T08:00:00Z", withRates = false)
        val csv = source.exportCsv()
        assertTrue(String(csv, Charsets.UTF_8).startsWith(Csv.BOM + "date,amount,currency,category,subcategory"))

        val target = Env()
        val result = target.transfer.importCsv(ByteArrayInputStream(csv))
        assertEquals(2, result.imported)
        assertEquals(emptyList<CsvRowError>(), result.errors)
        assertEquals(1, result.createdCategories) // «Кафе» под «Едой»; «Еда» и «Транспорт» нашлись по переводу

        val imported = target.db.expenseDao().getAll().map { e -> target.expenses.get(e.expense.id)!! }
        val latte = imported.first { it.amount.currency == CurrencyCode.RSD }
        assertEquals(Money(125050, CurrencyCode.RSD), latte.amount)
        assertEquals("Латте, \"большой\"", latte.comment)
        assertEquals(listOf("кофе", "работа"), latte.tags)
        assertEquals(BigDecimal("117.1523"), latte.rates!!.rsdPerEur) // снапшот курса сохранился
        assertEquals(1L, target.categories.get(latte.categoryId)!!.parentId)
        assertEquals(LocalDate.of(2026, 10, 14), latte.localDate)
        // Расход без курса: курсов в базе нет — остался без снапшота
        assertNull(imported.first { it.amount.currency == CurrencyCode.EUR }.rates)

        val again = target.transfer.importCsv(ByteArrayInputStream(csv))
        assertEquals(0, again.imported)
        assertEquals(2, again.duplicates)
    }

    @Test
    fun csvFromSpreadsheetWithErrors() = runTest {
        val env = Env()
        val csv = """
            Date;Amount;Currency;Category;Subcategory;Payment_method;Comment
            14.10.2026 13:45;1 250,50;rsd;Продукты;;Карта Intesa;
            2026-10-13;12.5;EUR;Подарки;Цветы;;маме
            вчера;100;RSD;Еда;;;
            2026-10-12;-5;RSD;Еда;;;
            2026-10-12;5;USD;Еда;;;
            2026-10-12;5;RSD;;;;
        """.trimIndent()
        val result = env.transfer.importCsv(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(2, result.imported)
        assertEquals(2, result.createdCategories) // «Подарки» и «Подарки › Цветы»
        assertEquals(1, result.createdPaymentMethods)
        assertEquals(
            listOf(
                CsvRowError(4, CsvErrorReason.BAD_DATE),
                CsvRowError(5, CsvErrorReason.BAD_AMOUNT),
                CsvRowError(6, CsvErrorReason.BAD_CURRENCY),
                CsvRowError(7, CsvErrorReason.MISSING_CATEGORY),
            ),
            result.errors,
        )
        val groceries = env.db.expenseDao().getAll().first { it.expense.categoryId == 2L }
        assertEquals(125050L, groceries.expense.amountMinor)
        // 14.10 13:45 по Белграду = 11:45 UTC
        assertEquals(Instant.parse("2026-10-14T11:45:00Z"), groceries.expense.occurredAt)
    }

    @Test(expected = BackupFormatException::class)
    fun csvWithoutRequiredColumnsIsRejected() = runTest {
        Env().transfer.importCsv(ByteArrayInputStream("foo,bar\n1,2".toByteArray()))
    }

    @Test
    fun jsonRoundTripRestoresEverything() = runTest {
        val source = Env()
        val cafe = source.categories.upsert(Category(name = "Кафе", iconKey = "local_cafe", color = 123, parentId = 1))
        val rec = source.recurring.upsert(
            RecurringExpense.create(Money(99900, CurrencyCode.RSD), 9, 1, Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 31)),
        )
        val e1 = source.add(Money(125050, CurrencyCode.RSD), cafe, "2026-10-14T11:45:00Z", listOf("кофе"), "латте")
        source.add(Money(500, CurrencyCode.EUR), 3, "2026-10-02T08:00:00Z", withRates = false)
        val budgetId = source.budgets.upsert(Budget(categoryId = 1, limit = Money(3000000, CurrencyCode.RSD)))
        source.budgets.markAlerted(budgetId, java.time.YearMonth.of(2026, 10), app.moneytracker.domain.model.BudgetLevel.WARNING)
        source.rateRepo.setManualRate(CurrencyCode.RSD, BigDecimal("117.25"), LocalDate.of(2026, 10, 14))
        source.settings.setDisplayCurrency(CurrencyCode.EUR)
        source.settings.setDefaultPaymentMethod(1)
        val json = source.exportJson()

        val target = Env()
        target.add(Money(1, CurrencyCode.RSD), 1, "2026-01-01T00:00:00Z") // будет заменено
        val result = target.transfer.importJson(ByteArrayInputStream(json))
        assertEquals(2, result.expenses)
        assertEquals(11, result.categories)

        assertEquals(source.expenses.get(e1), target.expenses.get(e1))
        assertEquals(source.db.expenseDao().getAll().map { it.expense }, target.db.expenseDao().getAll().map { it.expense })
        assertEquals(source.categories.getAll(), target.categories.getAll())
        assertEquals(source.budgets.getAll(), target.budgets.getAll())
        assertEquals(rec, target.recurring.get(rec)!!.id)
        assertEquals(BigDecimal("117.25"), target.rateRepo.current()?.snapshot?.rsdPerEur ?: target.db.maintenanceDao().allRates().single().perEur)
        assertEquals(CurrencyCode.EUR, target.settings.settings.first().displayCurrency)
        assertEquals(1L, target.settings.settings.first().defaultPaymentMethodId)
        // Отметка об уведомлении тоже переехала — повторного «80%» не будет
        assertTrue(!target.budgets.markAlerted(budgetId, java.time.YearMonth.of(2026, 10), app.moneytracker.domain.model.BudgetLevel.WARNING))
    }

    @Test
    fun brokenJsonLeavesDataUntouched() = runTest {
        val env = Env()
        env.add(Money(100, CurrencyCode.EUR), 1, "2026-10-14T10:00:00Z")
        val good = String(env.exportJson())

        for (bad in listOf("not json", """{"format":"other"}""", good.replace("\"categoryId\":1,", "\"categoryId\":999,"))) {
            try {
                env.transfer.importJson(ByteArrayInputStream(bad.toByteArray()))
                fail("Expected BackupFormatException for: ${bad.take(40)}")
            } catch (_: BackupFormatException) {
            }
            assertEquals(1, env.db.expenseDao().getAll().size)
            assertEquals(10, env.categories.getAll().size)
        }
    }

    @Test
    fun newerVersionIsRejected() = runTest {
        val env = Env()
        val newer = String(env.exportJson()).replace("\"version\":1", "\"version\":99")
        try {
            env.transfer.importJson(ByteArrayInputStream(newer.toByteArray()))
            fail()
        } catch (e: BackupFormatException) {
            assertEquals(BackupFormatException.Reason.NEWER_VERSION, e.reason)
        }
    }

    @Test
    fun databaseBackupIsValidSqliteCopy() = runTest {
        val env = Env(fileDb = true)
        env.add(Money(100, CurrencyCode.EUR), 1, "2026-10-14T10:00:00Z")
        val out = ByteArrayOutputStream()
        env.transfer.backupDatabase(out)
        val bytes = out.toByteArray()
        assertEquals("SQLite format 3", String(bytes, 0, 15, Charsets.US_ASCII))

        val copy = File(context.cacheDir, "copy.db").apply { writeBytes(bytes) }
        env.transfer.validateBackup(copy) // не бросает

        val garbage = File(context.cacheDir, "garbage.db").apply { writeText("definitely not sqlite") }
        try {
            env.transfer.validateBackup(garbage)
            fail()
        } catch (e: BackupFormatException) {
            assertEquals(BackupFormatException.Reason.NOT_A_BACKUP, e.reason)
        }
    }
}

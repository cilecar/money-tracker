package app.moneytracker.data.io

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import app.moneytracker.data.repository.RefundRepositoryImpl
import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
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
import app.moneytracker.domain.usecase.BudgetNotifier
import app.moneytracker.domain.usecase.CheckBudgetsUseCase
import app.moneytracker.domain.usecase.ObserveBudgetsUseCase
import app.moneytracker.domain.usecase.RunRecurringUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val expenses = ExpenseRepositoryImpl(db, db.expenseDao())
        val categories = CategoryRepositoryImpl(db.categoryDao())
        val budgets = BudgetRepositoryImpl(db.budgetDao())
        val recurring = RecurringRepositoryImpl(db, db.recurringDao())
        val runRecurring = RunRecurringUseCase(
            recurring,
            rateRepo,
            CheckBudgetsUseCase(ObserveBudgetsUseCase(budgets, expenses, categories, RefundRepositoryImpl(db.refundDao())), budgets, NoNotifier, clock),
            clock,
        )
        val transfer = DataTransferImpl(
            context, db, settings, rateRepo, DefaultNames(context), Json { ignoreUnknownKeys = true }, clock, runRecurring,
        )

        suspend fun add(amount: Money, category: Long, at: String, tags: List<String> = emptyList(), comment: String? = null, withRates: Boolean = true) =
            expenses.insert(
                Expense.create(amount, category, 1, Instant.parse(at), zone, if (withRates) rates else null, clock.instant(), comment, tags),
            )

        fun exportCsv() = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { transfer.exportCsv(it) } }.toByteArray()
        fun exportJson() = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { transfer.exportJson(it) } }.toByteArray()
        fun backupDb() = ByteArrayOutputStream().also { kotlinx.coroutines.runBlocking { transfer.backupDatabase(it) } }.toByteArray()
    }

    private object NoNotifier : BudgetNotifier {
        override fun notify(progress: BudgetProgress, level: BudgetLevel) = Unit
    }

    private val backupJson = Json { ignoreUnknownKeys = true }

    /** Экспорт с правкой: так собираем «битые» резервные копии. */
    private fun Env.exportJsonWith(edit: (BackupDto) -> BackupDto): ByteArray {
        val dto = backupJson.decodeFromString(BackupDto.serializer(), String(exportJson()))
        return backupJson.encodeToString(BackupDto.serializer(), edit(dto)).toByteArray()
    }

    private suspend fun Env.assertImportRejected(bytes: ByteArray, reason: BackupFormatException.Reason, what: String) {
        val before = db.expenseDao().getAll().size
        try {
            transfer.importJson(ByteArrayInputStream(bytes))
            fail("Expected $reason for: $what")
        } catch (e: BackupFormatException) {
            assertEquals(what, reason, e.reason)
        }
        assertEquals(what, before, db.expenseDao().getAll().size)
    }

    /** Наполненная база: расход, бюджет с отметкой, регулярный платёж, курс. */
    private suspend fun filledEnv(fileDb: Boolean = false) = Env(fileDb).apply {
        add(Money(100, CurrencyCode.EUR), 1, "2026-10-14T10:00:00Z")
        val budgetId = budgets.upsert(Budget(categoryId = 1, limit = Money(3000000, CurrencyCode.RSD)))
        budgets.markAlerted(budgetId, java.time.YearMonth.of(2026, 10), app.moneytracker.domain.model.BudgetLevel.WARNING)
        recurring.upsert(RecurringExpense.create(Money(99900, CurrencyCode.RSD), 9, 1, Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 31)))
        rateRepo.setManualRate(CurrencyCode.RSD, BigDecimal("117.25"), LocalDate.of(2026, 10, 14))
    }

    @Test
    fun importJsonWaitsForRunningRecurringPayments() = runTest {
        val json = filledEnv().exportJson()
        val target = Env()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Запуск регулярных платежей уже идёт и держит шаблоны в памяти.
        val run = launch(Dispatchers.Default) { target.runRecurring.exclusive { entered.complete(Unit); release.await() } }
        entered.await()

        val import = async(Dispatchers.Default) { target.transfer.importJson(ByteArrayInputStream(json)) }
        withContext(Dispatchers.Default) { delay(300) }
        assertFalse("import must wait for the run", import.isCompleted)
        assertTrue(target.recurring.observeAll().first().isEmpty())

        release.complete(Unit)
        assertEquals(1, import.await().recurring)
        run.join()
        assertEquals(1, target.recurring.observeAll().first().size)
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
        assertEquals(1, result.createdAccounts)
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
        source.settings.setDefaultAccount(1)
        val json = source.exportJson()

        val target = Env()
        target.add(Money(1, CurrencyCode.RSD), 1, "2026-01-01T00:00:00Z") // будет заменено
        val result = target.transfer.importJson(ByteArrayInputStream(json))
        assertEquals(2, result.expenses)
        assertEquals(12, result.categories)

        assertEquals(source.expenses.get(e1), target.expenses.get(e1))
        assertEquals(source.db.expenseDao().getAll().map { it.expense }, target.db.expenseDao().getAll().map { it.expense })
        assertEquals(source.categories.getAll(), target.categories.getAll())
        assertEquals(source.budgets.getAll(), target.budgets.getAll())
        assertEquals(rec, target.recurring.get(rec)!!.id)
        assertEquals(BigDecimal("117.25"), target.rateRepo.current()?.snapshot?.rsdPerEur ?: target.db.maintenanceDao().allRates().single().perEur)
        assertEquals(CurrencyCode.EUR, target.settings.settings.first().displayCurrency)
        assertEquals(1L, target.settings.settings.first().defaultAccountId)
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
            assertEquals(11, env.categories.getAll().size)
        }
    }

    @Test
    fun newerVersionIsRejected() = runTest {
        val env = Env()
        val newer = String(env.exportJson()).replace("\"version\":${BackupDto.VERSION}", "\"version\":99")
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

    @Test
    fun jsonWithInvalidRecurringIsRejected() = runTest {
        val env = filledEnv()
        val inconsistent = BackupFormatException.Reason.INCONSISTENT
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(recurring = d.recurring.map { it.copy(interval = 0) }) }, inconsistent, "interval 0")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(recurring = d.recurring.map { it.copy(dayOfMonth = 40) }) }, inconsistent, "dayOfMonth 40")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(recurring = d.recurring.map { it.copy(amountMinor = 0) }) }, inconsistent, "recurring amount 0")
    }

    @Test
    fun jsonWithDuplicateIdsIsRejectedNotCrashing() = runTest {
        val env = filledEnv()
        val inconsistent = BackupFormatException.Reason.INCONSISTENT
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(expenses = d.expenses + d.expenses) }, inconsistent, "duplicate expense")
        env.assertImportRejected(
            env.exportJsonWith { d -> d.copy(budgets = d.budgets + d.budgets.map { it.copy(id = it.id + 100) }) },
            inconsistent, "two budgets for one category",
        )
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(budgets = d.budgets + d.budgets) }, inconsistent, "duplicate budget")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(categories = d.categories + d.categories.last()) }, inconsistent, "duplicate category")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(accounts = d.accounts + d.accounts.last()) }, inconsistent, "duplicate method")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(recurring = d.recurring + d.recurring) }, inconsistent, "duplicate recurring")
    }

    @Test
    fun jsonWithNonPositiveNumbersIsRejected() = runTest {
        val env = filledEnv()
        val inconsistent = BackupFormatException.Reason.INCONSISTENT
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(exchangeRates = d.exchangeRates.map { it.copy(perEur = "0") }) }, inconsistent, "rate 0")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(exchangeRates = d.exchangeRates.map { it.copy(perEur = "-1") }) }, inconsistent, "rate -1")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(expenses = d.expenses.map { it.copy(amountMinor = -5) }) }, inconsistent, "amount -5")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(expenses = d.expenses.map { it.copy(amountMinor = 0) }) }, inconsistent, "amount 0")
        env.assertImportRejected(env.exportJsonWith { d -> d.copy(budgets = d.budgets.map { it.copy(limitMinor = 0) }) }, inconsistent, "limit 0")
        env.assertImportRejected(
            env.exportJsonWith { d -> d.copy(expenses = d.expenses.map { it.copy(rates = RatesDto("0", "95", "2026-10-01")) }) },
            inconsistent, "snapshot rate 0",
        )
    }

    @Test
    fun databaseBackupIncludesDataStuckInWal() = runTest {
        val env = Env(fileDb = true)
        env.add(Money(100, CurrencyCode.EUR), 1, "2026-10-14T10:00:00Z")
        // Читатель держит старый снимок — checkpoint не сможет перенести новые страницы из -wal в .db.
        val reader = SQLiteDatabase.openDatabase(context.getDatabasePath(AppDatabase.NAME).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            reader.beginTransactionReadOnly()
            reader.rawQuery("SELECT count(*) FROM expense", null).use { it.moveToFirst() }
            env.add(Money(200, CurrencyCode.EUR), 1, "2026-10-14T11:00:00Z")
            val bytes = env.backupDb()
            reader.endTransaction()

            val copy = File(context.cacheDir, "wal-copy.db").apply { writeBytes(bytes) }
            SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { c ->
                assertEquals(2L, android.database.DatabaseUtils.longForQuery(c, "SELECT count(*) FROM expense", null))
            }
        } finally {
            if (reader.inTransaction()) reader.endTransaction()
            reader.close()
        }
    }

    @Test
    fun databaseRestoreReplacesData() = runTest {
        val env = Env(fileDb = true)
        env.add(Money(100, CurrencyCode.EUR), 1, "2026-10-14T10:00:00Z")
        val backup = env.backupDb()
        env.add(Money(200, CurrencyCode.EUR), 1, "2026-10-14T11:00:00Z")

        env.transfer.restoreDatabase(ByteArrayInputStream(backup))

        val reopened = Env(fileDb = true)
        assertEquals(listOf(100L), reopened.db.expenseDao().getAll().map { it.expense.amountMinor })
        assertEquals(emptyList<String>(), context.getDatabasePath(AppDatabase.NAME).parentFile!!.list()!!.filter { it.endsWith(".restore") })
    }

    @Test
    fun failedDatabaseRestoreKeepsCurrentDatabaseOpen() = runTest {
        val env = Env(fileDb = true)
        env.add(Money(100, CurrencyCode.EUR), 1, "2026-10-14T10:00:00Z")
        val backup = env.backupDb()
        val dir = context.getDatabasePath(AppDatabase.NAME).parentFile!!
        // Каталог базы только для чтения — подменить файл не получится (как при нехватке места).
        dir.setWritable(false)
        try {
            env.transfer.restoreDatabase(ByteArrayInputStream(backup))
            fail("Expected IOException")
        } catch (_: IOException) {
        } finally {
            dir.setWritable(true)
        }
        assertTrue(env.db.isOpen)
        assertEquals(1, env.db.expenseDao().getAll().size)
    }

    @Test
    fun olderDatabaseBackupWithoutMigrationIsRejected() = runTest {
        val env = Env(fileDb = true)
        val copy = File(context.cacheDir, "old.db").apply { writeBytes(env.backupDb()) }
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 0 }
        try {
            env.transfer.validateBackup(copy)
            fail()
        } catch (e: BackupFormatException) {
            assertEquals(BackupFormatException.Reason.NOT_A_BACKUP, e.reason)
        }
    }

    /** Файл версии 1: старые имена полей, у наличных нет валюты — импорт достраивает счёт, как миграция 1→2. */
    @Test
    fun jsonVersion1BecomesAccounts() = runTest {
        val v1 = """
            {"format":"money-tracker-backup","version":1,"exportedAt":"2026-10-01T00:00:00Z",
             "settings":{"inputCurrency":"EUR","displayCurrency":"RSD","defaultPaymentMethodId":5,"themeMode":"SYSTEM","dynamicColor":true},
             "categories":[{"id":1,"name":"Еда","defaultKey":"food","iconKey":"restaurant","color":1,"sortOrder":0,"archived":false}],
             "paymentMethods":[
               {"id":5,"name":"Наличные","type":"CASH","defaultKey":"cash","sortOrder":0,"archived":false},
               {"id":6,"name":"Пустой","type":"CARD","sortOrder":1,"archived":false}],
             "expenses":[
               {"id":1,"amountMinor":1000,"currency":"EUR","categoryId":1,"paymentMethodId":5,"occurredAt":"2026-09-02T10:00:00Z","localDate":"2026-09-02","createdAt":"2026-09-02T10:00:00Z","updatedAt":"2026-09-02T10:00:00Z"},
               {"id":2,"amountMinor":500,"currency":"EUR","categoryId":1,"paymentMethodId":5,"occurredAt":"2026-08-20T10:00:00Z","localDate":"2026-08-20","createdAt":"2026-08-20T10:00:00Z","updatedAt":"2026-08-20T10:00:00Z"},
               {"id":3,"amountMinor":90000,"currency":"RSD","categoryId":1,"paymentMethodId":5,"occurredAt":"2026-09-05T10:00:00Z","localDate":"2026-09-05","createdAt":"2026-09-05T10:00:00Z","updatedAt":"2026-09-05T10:00:00Z"}],
             "budgets":[],"recurring":[],"exchangeRates":[]}
        """.trimIndent()
        val env = Env()
        env.transfer.importJson(ByteArrayInputStream(v1.toByteArray()))

        val accounts = env.db.accountDao().getAll(true).associateBy { it.id }
        assertEquals(CurrencyCode.EUR, accounts.getValue(5).currency)
        assertEquals(LocalDate.of(2026, 8, 20), accounts.getValue(5).openingDate)
        assertEquals(0L, accounts.getValue(5).openingBalanceMinor)
        assertEquals(CurrencyCode.RSD, accounts.getValue(6).currency)
        assertEquals(LocalDate.now(clock), accounts.getValue(6).openingDate)
        assertEquals(listOf(5L, 5L, 5L), env.db.expenseDao().getAll().map { it.expense.accountId })
        assertEquals(5L, env.settings.settings.first().defaultAccountId)
    }

    @Test
    fun jsonRoundTripKeepsAccountsAndAdjustments() = runTest {
        val source = Env()
        val dao = source.db.accountDao()
        val cash = dao.get(1)!!
        dao.upsert(cash.copy(currency = CurrencyCode.EUR, openingBalanceMinor = 12345, openingDate = LocalDate.of(2026, 3, 1)))
        dao.insertAdjustment(
            app.moneytracker.data.db.entity.BalanceAdjustmentEntity(0, 1, -500, Instant.parse("2026-10-01T10:00:00Z"), LocalDate.of(2026, 10, 1), "Сверка", Instant.parse("2026-10-01T10:00:00Z")),
        )
        val e = source.add(Money(1000, CurrencyCode.RSD), 1, "2026-10-02T10:00:00Z")
        source.db.openHelper.writableDatabase.execSQL("UPDATE expense SET account_amount_minor = 9 WHERE id = $e")

        val target = Env()
        target.transfer.importJson(ByteArrayInputStream(source.exportJson()))
        assertEquals(dao.getAll(true), target.db.accountDao().getAll(true))
        assertEquals(source.db.maintenanceDao().allAdjustments(), target.db.maintenanceDao().allAdjustments())
        assertEquals(9L, target.db.expenseDao().getAll().single().expense.accountAmountMinor)
    }

    /** Заголовок старых выгрузок «payment_method»; новый счёт берёт валюту и самую раннюю дату своих строк. */
    @Test
    fun csvWithOldHeaderCreatesAccountWithCurrency() = runTest {
        val csv = """
            date,amount,currency,category,payment_method
            2026-09-10 12:00,10,EUR,Еда,Revolut
            2026-09-01 12:00,5,EUR,Еда,Revolut
        """.trimIndent()
        val env = Env()
        val result = env.transfer.importCsv(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(2, result.imported)
        val revolut = env.db.accountDao().getAll(true).single { it.name == "Revolut" }
        assertEquals(CurrencyCode.EUR, revolut.currency)
        assertEquals(LocalDate.of(2026, 9, 1), revolut.openingDate)
    }

    @Test
    fun jsonRoundTripKeepsIncomesAndRefunds() = runTest {
        val source = Env()
        val e = source.add(Money(1000, CurrencyCode.EUR), 1, "2026-10-02T10:00:00Z")
        val incomeCats = source.db.incomeCategoryDao()
        val freelance = incomeCats.getAll(true).first { it.defaultKey == "freelance" }
        incomeCats.upsert(freelance.copy(name = "Заказы", defaultKey = null, archived = true))
        val incomes = app.moneytracker.data.repository.IncomeRepositoryImpl(source.db.incomeDao())
        val refunds = RefundRepositoryImpl(source.db.refundDao())
        incomes.insert(app.moneytracker.domain.model.Income.create(Money(500000, CurrencyCode.RSD), 1, freelance.id, Instant.parse("2026-10-03T10:00:00Z"), zone, rates, clock.instant(), "аванс"))
        refunds.insert(app.moneytracker.domain.model.Refund.create(Money(300, CurrencyCode.EUR), 1, e, 1, Instant.parse("2026-10-04T10:00:00Z"), zone, null, clock.instant()))

        val target = Env()
        target.transfer.importJson(ByteArrayInputStream(source.exportJson()))
        assertEquals(incomeCats.getAll(true), target.db.incomeCategoryDao().getAll(true))
        assertEquals(source.db.incomeDao().getAll(), target.db.incomeDao().getAll())
        assertEquals(source.db.refundDao().getAll(), target.db.refundDao().getAll())
    }

    /** В файлах до появления доходов категорий доходов нет — создаются стандартные. */
    @Test
    fun jsonWithoutIncomeCategoriesGetsDefaults() = runTest {
        val env = Env()
        val tree = Json.parseToJsonElement(String(env.exportJson())) as JsonObject
        val json = JsonObject(tree - "incomeCategories").toString()
        val target = Env()
        target.transfer.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(listOf("salary", "freelance", "gift", "income_other"), target.db.incomeCategoryDao().getAll(true).map { it.defaultKey })
    }

    @Test
    fun jsonRoundTripKeepsTransfersWithFee() = runTest {
        val source = Env()
        val accounts = source.db.accountDao()
        val card = accounts.upsert(accounts.get(1)!!.copy(id = 0, name = "Карта", currency = CurrencyCode.EUR, defaultKey = null))
        val transfers = app.moneytracker.data.repository.TransferRepositoryImpl(source.db)
        val at = Instant.parse("2026-10-05T10:00:00Z")
        val fee = Expense.create(Money(150, CurrencyCode.EUR), 1, card, at, zone, rates, clock.instant())
        transfers.save(app.moneytracker.domain.model.Transfer.create(card, 1, Money(10000, CurrencyCode.EUR), Money(1165000, CurrencyCode.RSD), at, zone, rates, clock.instant(), "обмен"), fee)

        val target = Env()
        target.transfer.importJson(ByteArrayInputStream(source.exportJson()))
        assertEquals(source.db.transferDao().getAll(), target.db.transferDao().getAll())
        val restored = target.db.transferDao().getAll().single()
        assertEquals(Money(150, CurrencyCode.EUR).minor, target.db.expenseDao().get(restored.feeExpenseId!!)!!.expense.amountMinor)
    }

    /** USDT-счёт, расход в USDT с курсом USDT в снапшоте и перевод USDT → RSD переживают выгрузку и загрузку. */
    @Test
    fun jsonRoundTripKeepsUsdt() = runTest {
        val usdtRates = rates.copy(usdtPerEur = BigDecimal("1.1650"))
        val source = Env()
        val accounts = source.db.accountDao()
        val bybit = accounts.upsert(accounts.get(1)!!.copy(id = 0, name = "Bybit", currency = CurrencyCode.USDT, defaultKey = null))
        val at = Instant.parse("2026-10-05T10:00:00Z")
        source.expenses.insert(Expense.create(Money(12_34, CurrencyCode.USDT), 1, bybit, at, zone, usdtRates, clock.instant()))
        app.moneytracker.data.repository.TransferRepositoryImpl(source.db).save(
            app.moneytracker.domain.model.Transfer.create(bybit, 1, Money(50_00, CurrencyCode.USDT), Money(4900_00, CurrencyCode.RSD), at, zone, usdtRates, clock.instant()),
            null,
        )
        for ((c, v) in listOf(CurrencyCode.RSD to "117", CurrencyCode.RUB to "95", CurrencyCode.USDT to "1.17")) {
            source.rateRepo.setManualRate(c, BigDecimal(v), LocalDate.of(2026, 10, 14))
        }
        // Валюта отображения USDT быть не может — такой файл импортирует остальное и её не трогает.
        val json = String(source.exportJson()).replace("\"displayCurrency\":\"RSD\"", "\"displayCurrency\":\"USDT\"")

        val target = Env()
        target.transfer.importJson(ByteArrayInputStream(json.toByteArray()))
        assertEquals(source.db.expenseDao().getAll().map { it.expense }, target.db.expenseDao().getAll().map { it.expense })
        assertEquals(source.db.transferDao().getAll(), target.db.transferDao().getAll())
        assertEquals(source.db.accountDao().getAll(true), target.db.accountDao().getAll(true))
        val restored = target.expenses.get(target.db.expenseDao().getAll().single().expense.id)!!
        assertEquals(BigDecimal("1.1650"), restored.rates!!.usdtPerEur)
        assertEquals(BigDecimal("1.17"), target.rateRepo.current()?.effective?.get(CurrencyCode.USDT)?.perEur)
        assertEquals(CurrencyCode.RSD, target.settings.settings.first().displayCurrency)
    }

    /** Файл версии 2 (до USDT): поля usdtPerEur нет — импорт проходит, курс USDT в снапшотах пустой. */
    @Test
    fun jsonVersion2WithoutUsdtImports() = runTest {
        val source = Env()
        source.add(Money(1000, CurrencyCode.EUR), 1, "2026-10-02T10:00:00Z")
        val tree = Json.parseToJsonElement(String(source.exportJson())) as JsonObject
        assertEquals(BackupDto.VERSION, 3)
        val v2 = tree.toString().replace("\"version\":3", "\"version\":2")
        assertTrue("в файле нет курса USDT", "usdtPerEur" !in v2)

        val target = Env()
        target.transfer.importJson(ByteArrayInputStream(v2.toByteArray()))
        val e = target.expenses.get(target.db.expenseDao().getAll().single().expense.id)!!
        assertEquals(rates, e.rates)
        assertNull(e.rates!!.usdtPerEur)
    }

    /** CSV: колонка rate_usdt_per_eur выгружается и читается; в старых файлах её нет. */
    @Test
    fun csvKeepsUsdtRate() = runTest {
        val source = Env()
        source.expenses.insert(Expense.create(Money(12_34, CurrencyCode.USDT), 1, 1, Instant.parse("2026-10-05T10:00:00Z"), zone, rates.copy(usdtPerEur = BigDecimal("1.1650")), clock.instant()))
        val target = Env()
        assertEquals(1, target.transfer.importCsv(ByteArrayInputStream(source.exportCsv())).imported)
        val e = target.expenses.get(target.db.expenseDao().getAll().single().expense.id)!!
        assertEquals(Money(12_34, CurrencyCode.USDT), e.amount)
        assertEquals(BigDecimal("1.1650"), e.rates!!.usdtPerEur)
        assertEquals(source.db.expenseDao().getAll().single().expense.eurMinor, e.converted!!.eurMinor)
    }

    /** Сумма перевода не в валюте счёта сломала бы баланс — такой файл отклоняется целиком. */
    @Test
    fun transferInWrongCurrencyIsRejected() = runTest {
        val source = Env()
        val accounts = source.db.accountDao()
        val card = accounts.upsert(accounts.get(1)!!.copy(id = 0, name = "Карта", currency = CurrencyCode.EUR, defaultKey = null))
        app.moneytracker.data.repository.TransferRepositoryImpl(source.db).save(
            app.moneytracker.domain.model.Transfer.create(card, 1, Money(100, CurrencyCode.EUR), Money(11700, CurrencyCode.RSD), Instant.parse("2026-10-05T10:00:00Z"), zone, null, clock.instant()),
            null,
        )
        val broken = String(source.exportJson()).replace("\"toCurrency\":\"RSD\"", "\"toCurrency\":\"RUB\"")
        try {
            Env().transfer.importJson(ByteArrayInputStream(broken.toByteArray()))
            fail()
        } catch (e: BackupFormatException) {
            assertEquals(BackupFormatException.Reason.INCONSISTENT, e.reason)
        }
    }
}

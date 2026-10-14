package app.moneytracker.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.repository.CategoryRepositoryImpl
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ExpenseDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl

    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val rates = RateSnapshot(BigDecimal("117.1523"), BigDecimal("95.4321"), LocalDate.of(2026, 10, 14))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
        categories = CategoryRepositoryImpl(db.categoryDao())
    }

    @After
    fun tearDown() = db.close()

    private fun expense(tags: List<String> = emptyList(), at: Instant = now) = Expense.create(
        amount = Money(250000, CurrencyCode.RSD),
        categoryId = 1,
        paymentMethodId = 1,
        occurredAt = at,
        zone = zone,
        rates = rates,
        now = now,
        comment = "обед",
        tags = tags,
    )

    @Test
    @Config(qualifiers = "ru")
    fun seedsDefaults() = runTest {
        val all = categories.observeAll().first()
        assertEquals(DefaultData.categories.size, all.size)
        assertEquals("Еда", all.first().name)
        assertEquals("food", all.first().defaultKey)
        assertEquals(1, db.paymentMethodDao().observeAll(false).first().size)
    }

    @Test
    @Config(qualifiers = "en")
    fun seedsLocalizedNamesForCurrentLocale() = runTest {
        assertEquals("Eating out", categories.observeAll().first().first().name)
    }

    @Test
    fun roundTripKeepsMoneyAndRatesExact() = runTest {
        val id = expenses.insert(expense(tags = listOf("кафе", "работа")))
        val loaded = expenses.get(id)!!
        assertEquals(Money(250000, CurrencyCode.RSD), loaded.amount)
        // BigDecimal пережил хранение в TEXT без потери знаков
        assertEquals(BigDecimal("117.1523"), loaded.rates!!.rsdPerEur)
        assertEquals(rates.convertAll(loaded.amount), loaded.converted)
        assertEquals(listOf("кафе", "работа"), loaded.tags)
        assertEquals(LocalDate.of(2026, 10, 14), loaded.localDate)
    }

    @Test
    fun tagsAreCaseInsensitiveForCyrillic() = runTest {
        val a = expenses.insert(expense(tags = listOf("Кафе")))
        val b = expenses.insert(expense(tags = listOf("кафе")))
        // Один и тот же тег: сохранилось первое написание
        assertEquals(listOf("Кафе"), expenses.get(a)!!.tags)
        assertEquals(listOf("Кафе"), expenses.get(b)!!.tags)
    }

    @Test
    fun updateReplacesTags() = runTest {
        val id = expenses.insert(expense(tags = listOf("a", "b")))
        val e = expenses.get(id)!!
        expenses.update(e.copy(tags = listOf("c")))
        assertEquals(listOf("c"), expenses.get(id)!!.tags)
    }

    @Test
    fun deleteAndUndoRestoresSameId() = runTest {
        val id = expenses.insert(expense(tags = listOf("x")))
        val backup = expenses.get(id)!!
        expenses.delete(id)
        assertNull(expenses.get(id))
        expenses.insert(backup)
        assertEquals(backup, expenses.get(id))
    }

    @Test
    fun observeBetweenFiltersByLocalDate() = runTest {
        expenses.insert(expense(at = Instant.parse("2026-10-01T09:00:00Z")))
        expenses.insert(expense(at = Instant.parse("2026-10-14T09:00:00Z")))
        // 23:30 UTC 31 октября — это уже 1 ноября по Белграду
        expenses.insert(expense(at = Instant.parse("2026-10-31T23:30:00Z")))
        val october = expenses.observeBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).first()
        assertEquals(2, october.size)
        assertTrue(october[0].occurredAt > october[1].occurredAt)
    }

    @Test
    fun fillRatesWritesOnlyRatesAndOnlyIfStillPending() = runTest {
        val id = expenses.insert(expense(tags = listOf("кафе")).copy(rates = null, converted = null))
        val read = expenses.get(id)!!
        // После чтения пользователь поменял комментарий — курсы ложатся, правка остаётся.
        expenses.update(read.copy(comment = "правка"))
        assertTrue(expenses.fillRates(read.withRates(rates, now)))
        val filled = expenses.get(id)!!
        assertEquals("правка", filled.comment)
        assertEquals(listOf("кафе"), filled.tags)
        assertEquals(rates.convertAll(filled.amount), filled.converted)

        // Курс уже есть — повторно не пишем.
        assertFalse(expenses.fillRates(read.withRates(rates, now)))
        // Сумма поменялась после чтения — пересчёт старой суммы не пишем.
        val other = expenses.insert(expense().copy(rates = null, converted = null))
        val stale = expenses.get(other)!!
        expenses.update(stale.copy(amount = Money(1, CurrencyCode.RSD)))
        assertFalse(expenses.fillRates(stale.withRates(rates, now)))
        assertNull(expenses.get(other)!!.rates)
        // Удалённый расход — не падение FK, а просто false.
        expenses.delete(other)
        assertFalse(expenses.fillRates(stale.withRates(rates, now)))
    }

    @Test
    fun updateFromStaleCopyKeepsRatesFilledMeanwhile() = runTest {
        val id = expenses.insert(expense().copy(rates = null, converted = null))
        val read = expenses.get(id)!! // экран редактирования прочитал расход без курса
        assertTrue(expenses.fillRates(read.withRates(rates, now))) // тем временем курс заполнился

        // Сохранение копии без курса не затирает его; новая сумма пересчитана по тому же курсу.
        expenses.update(read.withAmount(Money(100000, CurrencyCode.RSD), now).copy(comment = "правка"))
        val saved = expenses.get(id)!!
        assertEquals("правка", saved.comment)
        assertEquals(rates, saved.rates)
        assertEquals(rates.convertAll(Money(100000, CurrencyCode.RSD)), saved.converted)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun foreignKeysAreEnforced() = runTest {
        expenses.insert(expense().copy(categoryId = 999))
    }
}

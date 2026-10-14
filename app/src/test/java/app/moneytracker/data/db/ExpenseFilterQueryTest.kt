package app.moneytracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ExpenseFilterQueryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ExpenseRepositoryImpl
    private val rates = RateSnapshot(BigDecimal("100"), BigDecimal("100"), LocalDate.of(2026, 10, 1))

    // id категорий/способов из сидера: 1 = Еда, 2 = Продукты; способ оплаты 1 = Наличные
    private lateinit var ids: Map<String, Long>

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        repo = ExpenseRepositoryImpl(db.expenseDao())

        suspend fun add(key: String, day: Int, amount: Money, category: Long, comment: String?, tags: List<String>, withRates: Boolean = true) =
            key to repo.insert(
                Expense.create(
                    amount, category, 1, Instant.parse("2026-10-%02dT12:00:00Z".format(day)), ZoneOffset.UTC,
                    if (withRates) rates else null, Instant.parse("2026-10-%02dT12:00:00Z".format(day)), comment, tags,
                ),
            )
        ids = mapOf(
            add("coffee", 1, Money(30000, CurrencyCode.RSD), 1, "Кофе с коллегами", listOf("Работа")),
            add("market", 5, Money(2000, CurrencyCode.EUR), 2, "Рынок", listOf("дом")),
            add("pending", 10, Money(50000, CurrencyCode.RSD), 2, null, listOf("Дом"), withRates = false),
            add("pendingEur", 12, Money(900, CurrencyCode.EUR), 1, "ужин", emptyList(), withRates = false),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun find(filter: ExpenseFilter) = repo.observe(filter).first().map { e -> ids.entries.first { it.value == e.id }.key }

    @Test
    fun emptyFilterReturnsAllNewestFirst() = runTest {
        assertEquals(listOf("pendingEur", "pending", "market", "coffee"), find(ExpenseFilter()))
    }

    @Test
    fun cyrillicSearchIsCaseInsensitive() = runTest {
        assertEquals(listOf("coffee"), find(ExpenseFilter(query = "КОФЕ")))
        assertEquals(listOf("coffee"), find(ExpenseFilter(query = "работа"))) // по тегу
    }

    @Test
    fun byCategoryAndTag() = runTest {
        assertEquals(listOf("pending", "market"), find(ExpenseFilter(categoryIds = setOf(2))))
        assertEquals(listOf("pending", "market"), find(ExpenseFilter(tags = setOf("ДОМ"))))
    }

    @Test
    fun byDates() = runTest {
        assertEquals(
            listOf("pending", "market"),
            find(ExpenseFilter(from = LocalDate.of(2026, 10, 2), to = LocalDate.of(2026, 10, 10))),
        )
    }

    @Test
    fun byAmountUsesConvertedValueAndOriginalForPending() = runTest {
        // ≥ 400 RSD: кофе 300 RSD — нет; рынок 20 EUR = 2000 RSD — да; pending 500 RSD — да (та же валюта);
        // pendingEur 9 EUR без курса — неизвестно в RSD, не попадает.
        assertEquals(listOf("pending", "market"), find(ExpenseFilter(minAmount = Money(40000, CurrencyCode.RSD))))
        assertEquals(
            listOf("pendingEur", "market"),
            find(ExpenseFilter(minAmount = Money(500, CurrencyCode.EUR), maxAmount = Money(2000, CurrencyCode.EUR))),
        )
    }

    @Test
    fun recentCategoriesAndTags() = runTest {
        assertEquals(listOf(1L, 2L), repo.observeRecentCategoryIds(5).first())
        assertEquals(listOf("Работа", "дом").sorted(), repo.observeTags().first().sorted())
        assertEquals("дом", repo.observeTags().first().first()) // самый частый
    }
}

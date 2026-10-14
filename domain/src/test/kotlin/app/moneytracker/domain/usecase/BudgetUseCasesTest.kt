package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeBudgetRepository
import app.moneytracker.domain.fake.FakeCategoryRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeRefundRepository
import app.moneytracker.domain.fake.RecordingNotifier
import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class BudgetUseCasesTest {

    private var now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val rates = RateSnapshot(BigDecimal("100"), BigDecimal("100"), LocalDate.of(2026, 10, 1))

    private val categories = FakeCategoryRepository(
        listOf(
            Category(id = 1, name = "Еда", iconKey = "", color = 0),
            Category(id = 2, name = "Кафе", iconKey = "", color = 0, parentId = 1),
            Category(id = 3, name = "Транспорт", iconKey = "", color = 0),
        ),
    )
    private val expenses = FakeExpenseRepository()
    private val budgets = FakeBudgetRepository()
    private val notifier = RecordingNotifier()
    private val refunds = FakeRefundRepository()
    private val observe = ObserveBudgetsUseCase(budgets, expenses, categories, refunds)
    private val check = CheckBudgetsUseCase(observe, budgets, notifier, clock)
    private val save = SaveBudgetUseCase(budgets, check)

    private suspend fun spend(eurCents: Long, category: Long = 1, day: Int = 10, currency: CurrencyCode = CurrencyCode.EUR, withRates: Boolean = true) {
        val at = Instant.parse("2026-10-%02dT12:00:00Z".format(day))
        expenses.insert(Expense.create(Money(eurCents, currency), category, 1, at, ZoneOffset.UTC, if (withRates) rates else null, at))
    }

    private suspend fun progress(month: YearMonth = YearMonth.of(2026, 10)) = observe(month).first()

    @Test
    fun `parent budget includes subcategories, other categories excluded`() = runTest {
        save(Budget(categoryId = 1, limit = Money(10000, CurrencyCode.EUR)))
        spend(5000, category = 1)
        spend(3000, category = 2)
        spend(9900, category = 3)
        val p = progress().single()
        assertEquals(Money(8000, CurrencyCode.EUR), p.spent)
        assertEquals(BudgetLevel.WARNING, p.level) // ровно 80% — уже предупреждение
        assertEquals(Money(2000, CurrencyCode.EUR), p.remaining)
    }

    @Test
    fun `budget in RSD converts by snapshot and counts pending separately`() = runTest {
        save(Budget(categoryId = 1, limit = Money(1000000, CurrencyCode.RSD)))
        spend(5000) // 50 EUR = 5000 RSD
        spend(700, withRates = false) // EUR без курса — неизвестно в RSD
        spend(100000, currency = CurrencyCode.RSD, withRates = false) // 1000 RSD — известно
        val p = progress().single()
        assertEquals(Money(600000, CurrencyCode.RSD), p.spent)
        assertEquals(1, p.withoutRates)
    }

    @Test
    fun `other months are not counted`() = runTest {
        save(Budget(categoryId = 1, limit = Money(10000, CurrencyCode.EUR)))
        spend(5000)
        assertEquals(Money(0, CurrencyCode.EUR), progress(YearMonth.of(2026, 11)).single().spent)
    }

    @Test
    fun `notifies once per threshold`() = runTest {
        val id = save(Budget(categoryId = 1, limit = Money(10000, CurrencyCode.EUR)))
        spend(7900); check()
        assertEquals(emptyList<Any>(), notifier.sent)

        spend(100); check() // 80%
        check()
        assertEquals(listOf(id to BudgetLevel.WARNING), notifier.sent)

        spend(2500); check() // 105%
        check()
        assertEquals(listOf(id to BudgetLevel.WARNING, id to BudgetLevel.EXCEEDED), notifier.sent)
    }

    @Test
    fun `jumping over both thresholds sends only exceeded`() = runTest {
        val id = save(Budget(categoryId = 1, limit = Money(10000, CurrencyCode.EUR)))
        spend(15000); check()
        check()
        assertEquals(listOf(id to BudgetLevel.EXCEEDED), notifier.sent)
    }

    @Test
    fun `lowering limit below spent notifies immediately`() = runTest {
        spend(6000)
        val id = save(Budget(categoryId = 1, limit = Money(10000, CurrencyCode.EUR)))
        assertEquals(emptyList<Any>(), notifier.sent)
        save(Budget(id = id, categoryId = 1, limit = Money(5000, CurrencyCode.EUR)))
        assertEquals(listOf(id to BudgetLevel.EXCEEDED), notifier.sent)
    }

    @Test
    fun `new month starts fresh`() = runTest {
        val id = save(Budget(categoryId = 1, limit = Money(10000, CurrencyCode.EUR)))
        spend(9000); check()
        now = Instant.parse("2026-11-02T10:00:00Z")
        val at = Instant.parse("2026-11-01T12:00:00Z")
        expenses.insert(Expense.create(Money(8500, CurrencyCode.EUR), 1, 1, at, ZoneOffset.UTC, rates, at))
        check()
        assertEquals(listOf(id to BudgetLevel.WARNING, id to BudgetLevel.WARNING), notifier.sent)
    }

    @Test
    fun `daily allowance`() {
        val p = BudgetProgress(Budget(1, 1, Money(31000, CurrencyCode.EUR)), null, Money(17000, CurrencyCode.EUR), 0)
        val month = YearMonth.of(2026, 10)
        // осталось 140 € на 18 дней (14..31) → 7.77 €
        assertEquals(Money(777, CurrencyCode.EUR), p.dailyAllowance(month, LocalDate.of(2026, 10, 14)))
        assertNull(p.dailyAllowance(month, LocalDate.of(2026, 11, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero limit rejected`() = runTest {
        save(Budget(categoryId = 1, limit = Money(0, CurrencyCode.EUR)))
    }
}

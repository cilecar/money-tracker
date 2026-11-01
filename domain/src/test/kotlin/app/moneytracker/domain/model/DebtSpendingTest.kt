package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Доли и «за меня заплатили» в аналитике, бюджетах и итоге месяца: считается только моя доля. */
class DebtSpendingTest {

    // 1 EUR = 100 RSD = 100 RUB
    private val rates = RateSnapshot(BigDecimal("100"), BigDecimal("100"), LocalDate.of(2026, 9, 1))
    private val food = Category(id = 1, name = "Еда", iconKey = "", color = 0)
    private val cafe = Category(id = 2, name = "Кафе", iconKey = "", color = 0, parentId = 1)
    private val categories = listOf(food, cafe).associateBy { it.id }
    private val card = Account(id = 1, name = "Карта", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = LocalDate.of(2020, 1, 1))
    private val october = AnalyticsPeriod.Month(YearMonth.of(2026, 10))
    private val today = LocalDate.of(2026, 10, 20)

    private fun at(date: String) = Instant.parse("${date}T12:00:00Z")
    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

    private fun dinner(id: Long = 7, date: String = "2026-10-12", amount: Money = rsd(300000), withRates: Boolean = true) =
        Expense.create(amount, 2, 1, at(date), ZoneOffset.UTC, rates.takeIf { withRates }, at(date)).copy(id = id)

    private fun share(id: Long, expenseId: Long = 7, amount: Money = rsd(100000)) =
        ExpenseShare(id = id, expenseId = expenseId, personId = id, amount = amount, createdAt = at("2026-10-12"), updatedAt = at("2026-10-12"))

    private fun paidForMe(date: String = "2026-10-15", amount: Money = rsd(40000)) =
        PaidForMe.create(amount, 1, 1, at(date), ZoneOffset.UTC, rates, at(date)).copy(id = 3)

    private fun report(expenses: List<Expense>, shares: List<ExpenseShare> = emptyList(), paid: List<PaidForMe> = emptyList()) =
        Analytics.build(expenses, october, CurrencyCode.RSD, categories, mapOf(1L to card), today, shares = shares, paidForMe = paid)

    @Test
    fun `shares reduce category on purchase date`() {
        // Погашение в другой день ничего не меняет: доля считается по дате покупки.
        val r = report(listOf(dinner()), shares = listOf(share(1), share(2)))
        assertEquals(rsd(100000), r.total.total)
        assertEquals(rsd(100000), r.categories.single().amount)
        assertEquals(rsd(100000), r.buckets.single { it.start == LocalDate.of(2026, 10, 12) }.amount)
        assertEquals(rsd(100000), r.accounts.single().amount)
        assertEquals(card, r.accounts.single().method)
    }

    @Test
    fun `share of expense outside the list is ignored`() {
        // Доля расхода из другого периода сюда не попала: её расход не в списке.
        val r = report(listOf(dinner()), shares = listOf(share(1), share(9, expenseId = 99)))
        assertEquals(rsd(200000), r.total.total)
    }

    @Test
    fun `paid for me goes to paid by others slice`() {
        val r = report(listOf(dinner()), paid = listOf(paidForMe()))
        assertEquals(rsd(340000), r.total.total)
        val others = r.accounts.single { it.paidByOthers }
        assertNull(others.method)
        assertEquals(rsd(40000), others.amount)
        assertEquals(rsd(300000), r.accounts.single { !it.paidByOthers }.amount)
        // «Кафе» — подкатегория «Еды»: ужин и кино в одном срезе.
        assertEquals(rsd(340000), r.categories.single { it.category == food }.amount)
    }

    @Test
    fun `budget counts my share and paid for me`() {
        val progress = BudgetProgress.compute(
            listOf(Budget(id = 1, categoryId = 1, limit = rsd(500000))),
            listOf(dinner()),
            categories,
            shares = listOf(share(1), share(2)),
            paidForMe = listOf(paidForMe()),
        ).single()
        assertEquals(rsd(140000), progress.spent)
    }

    @Test
    fun `month summary spends my share only`() {
        val s = MonthSummary.of(
            YearMonth.of(2026, 10), today, CurrencyCode.RSD,
            expenses = listOf(dinner()), refunds = emptyList(), incomes = emptyList(),
            shares = listOf(share(1), share(2)), paidForMe = listOf(paidForMe(), paidForMe(date = "2026-09-30")),
        )
        assertEquals(rsd(140000), s.spent.total)
    }

    @Test
    fun `share of expense without rates counts as without rates`() {
        val expense = dinner(amount = Money(3000, CurrencyCode.EUR), withRates = false)
        val shares = listOf(share(1, amount = Money(1000, CurrencyCode.EUR)))
        val pending = Total.spending(listOf(expense), emptyList(), CurrencyCode.RSD, shares = shares)
        assertEquals(2, pending.withoutRates)
        assertEquals(rsd(0), pending.total)

        // Курс пришёл к расходу — доля считается сама, без отдельного заполнения.
        val filled = expense.withRates(rates, at("2026-10-13"))
        val total = Total.spending(listOf(filled), emptyList(), CurrencyCode.RSD, shares = shares)
        assertEquals(0, total.withoutRates)
        assertEquals(rsd(200000), total.total)
    }

    @Test
    fun `spending items combine all sources`() {
        val refund = Refund.create(rsd(10000), 1, 7, 2, at("2026-10-13"), ZoneOffset.UTC, rates, at("2026-10-13"))
        val items = Spending.items(listOf(dinner()), listOf(refund), listOf(share(1)), listOf(paidForMe()))
        assertEquals(4, items.size)
        assertEquals(rsd(300000 - 10000 - 100000 + 40000), Total.of(items, CurrencyCode.RSD).total)
        assertTrue(items.any { it.accountId == PaidForMe.NO_ACCOUNT })
    }
}

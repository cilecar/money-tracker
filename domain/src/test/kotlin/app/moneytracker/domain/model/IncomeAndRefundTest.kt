package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

/** Возвраты в аналитике и бюджетах, доходы и «Осталось до конца месяца». */
class IncomeAndRefundTest {

    // 1 EUR = 100 RSD = 100 RUB
    private val rates = RateSnapshot(BigDecimal("100"), BigDecimal("100"), LocalDate.of(2026, 9, 1))
    private val food = Category(id = 1, name = "Еда", iconKey = "", color = 0)
    private val cafe = Category(id = 2, name = "Кафе", iconKey = "", color = 0, parentId = 1)
    private val transport = Category(id = 3, name = "Транспорт", iconKey = "", color = 0)
    private val categories = listOf(food, cafe, transport).associateBy { it.id }
    private val cash = Account(id = 1, name = "Наличные", type = AccountType.CASH, currency = CurrencyCode.EUR, openingDate = LocalDate.of(2020, 1, 1))
    private val salary = IncomeCategory(id = 1, name = "Зарплата", iconKey = "", color = 0)
    private val gift = IncomeCategory(id = 2, name = "Подарок", iconKey = "", color = 0)

    private val october = AnalyticsPeriod.Month(YearMonth.of(2026, 10))
    private val today = LocalDate.of(2026, 10, 14)

    private fun at(date: String) = Instant.parse("${date}T12:00:00Z")
    private fun eur(minor: Long) = Money(minor, CurrencyCode.EUR)

    private fun e(date: String, amount: Money, category: Long, withRates: Boolean = true) =
        Expense.create(amount, category, 1, at(date), ZoneOffset.UTC, rates.takeIf { withRates }, at(date))

    private fun refund(date: String, amount: Money, category: Long, expenseId: Long? = 1, withRates: Boolean = true) =
        Refund.create(amount, 1, expenseId, category, at(date), ZoneOffset.UTC, rates.takeIf { withRates }, at(date))

    private fun income(date: String, amount: Money, category: Long = 1, withRates: Boolean = true) =
        Income.create(amount, 1, category, at(date), ZoneOffset.UTC, rates.takeIf { withRates }, at(date))

    private fun report(expenses: List<Expense>, refunds: List<Refund> = emptyList(), incomes: List<Income> = emptyList()) =
        Analytics.build(
            expenses, october, CurrencyCode.EUR, categories, mapOf(1L to cash), today,
            refunds = refunds, incomes = incomes, incomeCategories = listOf(salary, gift).associateBy { it.id },
        )

    @Test
    fun `refund reduces its category and total, not income`() {
        val r = report(
            expenses = listOf(e("2026-10-02", eur(5000), 1), e("2026-10-03", eur(2000), 3)),
            // Возврат за кафе (подкатегория) уменьшает «Еду».
            refunds = listOf(refund("2026-10-05", Money(150000, CurrencyCode.RSD), 2)),
        )
        assertEquals(eur(5500), r.total.total)
        assertEquals(eur(3500), r.categories.single { it.category == food }.amount)
        assertEquals(eur(2000), r.categories.single { it.category == transport }.amount)
        assertEquals(eur(0), r.income.total)
        // Столбец дня возврата — минус.
        assertEquals(eur(-1500), r.buckets.single { it.start == LocalDate.of(2026, 10, 5) }.amount)
        assertEquals(eur(5500), r.accounts.single().amount)
    }

    @Test
    fun `refund counts in the month of the refund date`() {
        val r = report(
            expenses = listOf(e("2026-09-28", eur(4000), 1), e("2026-10-02", eur(1000), 1)),
            refunds = listOf(refund("2026-10-01", eur(4000), 1)),
        )
        // Сентябрьская покупка остаётся в сентябре, возврат уменьшает октябрь.
        assertEquals(eur(-3000), r.total.total)
        assertEquals(eur(4000), r.previousTotal.total)
        // Категория с отрицательной суммой в разбивку не попадает.
        assertTrue(r.categories.isEmpty())
    }

    @Test
    fun `refund of a deleted expense still uses the copied category`() {
        val r = report(expenses = listOf(e("2026-10-02", eur(3000), 3)), refunds = listOf(refund("2026-10-03", eur(1000), 3, expenseId = null)))
        assertEquals(eur(2000), r.categories.single().amount)
    }

    @Test
    fun `refund without rates in another currency is counted as missing`() {
        val r = report(expenses = listOf(e("2026-10-02", eur(3000), 1)), refunds = listOf(refund("2026-10-03", Money(100, CurrencyCode.RUB), 1, withRates = false)))
        assertEquals(eur(3000), r.total.total)
        assertEquals(1, r.total.withoutRates)
    }

    @Test
    fun `income totals, net and slices`() {
        val r = report(
            expenses = listOf(e("2026-10-02", eur(30000), 1)),
            incomes = listOf(
                income("2026-10-01", Money(10000000, CurrencyCode.RSD)), // 1000 €
                income("2026-10-10", eur(5000), category = 2),
                income("2026-09-30", eur(100000)),
            ),
        )
        assertEquals(eur(105000), r.income.total)
        assertEquals(eur(100000), r.previousIncome.total)
        assertEquals(eur(75000), r.net)
        assertEquals(listOf(salary, gift), r.incomeCategories.map { it.category })
        // Доход не уменьшает траты.
        assertEquals(eur(30000), r.total.total)
    }

    @Test
    fun `budget spending is reduced by refunds of the category subtree`() {
        val budget = Budget(id = 1, categoryId = 1, limit = eur(10000))
        val progress = BudgetProgress.compute(
            listOf(budget),
            listOf(e("2026-10-02", eur(9000), 2)),
            categories,
            listOf(refund("2026-10-03", eur(2000), 2), refund("2026-10-03", eur(500), 3)),
        ).single()
        assertEquals(eur(7000), progress.spent)
        assertEquals(BudgetLevel.OK, progress.level)
    }

    @Test
    fun `month summary - remaining and per day`() {
        val s = MonthSummary.of(
            YearMonth.of(2026, 10), today, CurrencyCode.EUR,
            expenses = listOf(e("2026-10-02", eur(40000), 1), e("2026-09-30", eur(99900), 1)),
            refunds = listOf(refund("2026-10-03", eur(5000), 1)),
            incomes = listOf(income("2026-10-01", eur(100000)), income("2026-11-01", eur(99900))),
        )
        assertEquals(eur(100000), s.income.total)
        assertEquals(eur(35000), s.spent.total)
        assertEquals(eur(65000), s.remaining)
        // 14..31 октября — 18 дней, считая сегодня; 650 / 18 = 36.111… → вниз до 36.11.
        assertEquals(18L, s.daysLeft)
        assertEquals(eur(3611), s.perDay)
        assertFalse(s.overspent)
    }

    @Test
    fun `month summary - last day of month gets the whole remainder`() {
        val s = MonthSummary.of(YearMonth.of(2026, 10), LocalDate.of(2026, 10, 31), CurrencyCode.EUR, emptyList(), emptyList(), listOf(income("2026-10-01", eur(12345))))
        assertEquals(1L, s.daysLeft)
        assertEquals(eur(12345), s.perDay)
    }

    @Test
    fun `month summary - overspent has no per day and keeps negative remainder`() {
        val s = MonthSummary.of(
            YearMonth.of(2026, 10), today, CurrencyCode.EUR,
            listOf(e("2026-10-02", eur(20000), 1)), emptyList(), listOf(income("2026-10-01", eur(15000))),
        )
        assertEquals(eur(-5000), s.remaining)
        assertTrue(s.overspent)
        assertNull(s.perDay)
    }

    @Test
    fun `month summary - operations without rates are reported`() {
        val s = MonthSummary.of(
            YearMonth.of(2026, 10), today, CurrencyCode.EUR,
            listOf(e("2026-10-02", Money(100, CurrencyCode.RSD), 1, withRates = false)), emptyList(),
            listOf(income("2026-10-01", Money(100, CurrencyCode.RUB), withRates = false)),
        )
        assertEquals(2, s.withoutRates)
        assertEquals(eur(0), s.remaining)
    }

    @Test
    fun `month summary - past month has no days left`() {
        val s = MonthSummary.of(YearMonth.of(2026, 9), today, CurrencyCode.EUR, emptyList(), emptyList(), listOf(income("2026-09-01", eur(100))))
        assertEquals(0L, s.daysLeft)
        assertNull(s.perDay)
    }

    @Test
    fun `feed filter - incomes hidden by category and tag filters, refunds follow category`() {
        val inc = income("2026-10-01", eur(100)).copy(comment = "Премия за октябрь")
        val ref = refund("2026-10-03", eur(100), 2).copy(comment = "Возврат")
        assertTrue(ExpenseFilter().matches(inc))
        assertTrue(ExpenseFilter(query = "премия").matches(inc))
        assertFalse(ExpenseFilter(categoryIds = setOf(1)).matches(inc))
        assertFalse(ExpenseFilter(tags = setOf("x")).matches(ref))
        assertTrue(ExpenseFilter(categoryIds = setOf(1, 2)).matches(ref))
        assertFalse(ExpenseFilter(categoryIds = setOf(3)).matches(ref))
        assertFalse(ExpenseFilter(kinds = setOf(OperationKind.EXPENSE)).matches(inc))
        assertTrue(ExpenseFilter(kinds = setOf(OperationKind.INCOME)).matches(inc))
        assertFalse(ExpenseFilter(accountIds = setOf(2)).matches(inc))
        assertFalse(ExpenseFilter(from = LocalDate.of(2026, 10, 2)).matches(inc))
        // Сумма — по снапшоту в валюте границы: 1 € = 100 RSD.
        assertTrue(ExpenseFilter(minAmount = Money(10000, CurrencyCode.RSD)).matches(inc))
        assertFalse(ExpenseFilter(minAmount = Money(10001, CurrencyCode.RSD)).matches(inc))
    }

    @Test
    fun `feed merges kinds by time and day total nets refunds`() {
        val sections = ExpenseSections.byDay(
            Operations(
                expenses = listOf(e("2026-10-02", eur(3000), 1)),
                incomes = listOf(Income.create(eur(5000), 1, 1, Instant.parse("2026-10-02T18:00:00Z"), ZoneOffset.UTC, rates, at("2026-10-02"))),
                refunds = listOf(Refund.create(eur(1000), 1, 1, 1, Instant.parse("2026-10-02T08:00:00Z"), ZoneOffset.UTC, rates, at("2026-10-02"))),
            ),
            categories, mapOf(1L to cash), listOf(salary).associateBy { it.id }, CurrencyCode.EUR,
        )
        val day = sections.single()
        assertEquals(listOf(IncomeItem::class, ExpenseItem::class, RefundItem::class), day.items.map { it::class })
        assertEquals(eur(2000), day.total.total)
        assertEquals(salary, (day.items[0] as IncomeItem).category)
    }
}

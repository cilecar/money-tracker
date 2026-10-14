package app.moneytracker.domain.model

import app.moneytracker.domain.model.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExpenseSectionsTest {

    private val rates = RateSnapshot(BigDecimal("117"), BigDecimal("100"), LocalDate.of(2026, 10, 1))
    private val food = Category(id = 1, name = "Еда", iconKey = "restaurant", color = 0)
    private val cafe = Category(id = 2, name = "Кафе", iconKey = "local_cafe", color = 0, parentId = 1)
    private val cash = Account(id = 1, name = "Наличные", type = AccountType.CASH, currency = CurrencyCode.RSD, openingDate = OPENING)

    private fun expense(day: Int, hour: Int, amount: Money, categoryId: Long = 1, withRates: Boolean = true): Expense {
        val at = Instant.parse("2026-10-%02dT%02d:00:00Z".format(day, hour))
        return Expense.create(amount, categoryId, 1, at, ZoneOffset.UTC, if (withRates) rates else null, at)
    }

    private fun sections(list: List<Expense>, currency: CurrencyCode) =
        ExpenseSections.byDay(list.sortedByDescending { it.occurredAt }, mapOf(1L to food, 2L to cafe), mapOf(1L to cash), currency)

    @Test
    fun `groups by day newest first with totals in display currency`() {
        val result = sections(
            listOf(
                expense(13, 9, Money(11700, CurrencyCode.RSD)), // 1 EUR
                expense(14, 9, Money(500, CurrencyCode.EUR)),
                expense(14, 18, Money(10000, CurrencyCode.RUB)), // 1 EUR
            ),
            CurrencyCode.EUR,
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 13)), result.map { it.date })
        assertEquals(Money(600, CurrencyCode.EUR), result[0].total.total)
        assertEquals(Money(100, CurrencyCode.EUR), result[1].total.total)
        assertEquals(18, result[0].items.first().expense.occurredAt.atZone(ZoneOffset.UTC).hour)
    }

    @Test
    fun `day total in RSD sums snapshot conversions`() {
        val result = sections(
            listOf(expense(14, 9, Money(500, CurrencyCode.EUR)), expense(14, 10, Money(5000, CurrencyCode.RSD))),
            CurrencyCode.RSD,
        )
        assertEquals(Money(58500 + 5000, CurrencyCode.RSD), result.single().total.total)
    }

    @Test
    fun `expenses without rates are counted separately unless already in display currency`() {
        val list = listOf(
            expense(14, 9, Money(5000, CurrencyCode.RSD), withRates = false),
            expense(14, 10, Money(300, CurrencyCode.EUR), withRates = false),
            expense(14, 11, Money(100, CurrencyCode.EUR)),
        )
        val total = sections(list, CurrencyCode.RSD).single().total
        assertEquals(Money(5000 + 11700, CurrencyCode.RSD), total.total)
        assertEquals(1, total.withoutRates)
    }

    @Test
    fun `details carry category parent and payment method`() {
        val item = sections(listOf(expense(14, 9, Money(100, CurrencyCode.EUR), categoryId = 2)), CurrencyCode.EUR)
            .single().items.single()
        assertEquals(cafe, item.category)
        assertEquals(food, item.parentCategory)
        assertEquals(cash, item.account)
    }

    @Test
    fun `empty list gives no sections`() {
        assertEquals(emptyList<DaySection>(), sections(emptyList(), CurrencyCode.RSD))
    }

    @Test
    fun `amountIn falls back to original currency only`() {
        val pending = expense(14, 9, Money(5000, CurrencyCode.RSD), withRates = false)
        assertEquals(Money(5000, CurrencyCode.RSD), pending.amountIn(CurrencyCode.RSD))
        assertNull(pending.amountIn(CurrencyCode.EUR))
    }
}

private val OPENING: LocalDate = LocalDate.of(2020, 1, 1)

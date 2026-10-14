package app.moneytracker.domain.model

import app.moneytracker.domain.model.CurrencyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class AnalyticsTest {

    // 1 EUR = 100 RSD = 100 RUB — чтобы итоги считались в уме
    private val rates = RateSnapshot(BigDecimal("100"), BigDecimal("100"), LocalDate.of(2026, 9, 1))
    private val food = Category(id = 1, name = "Еда", iconKey = "", color = 0)
    private val cafe = Category(id = 2, name = "Кафе", iconKey = "", color = 0, parentId = 1)
    private val transport = Category(id = 3, name = "Транспорт", iconKey = "", color = 0)
    private val cash = Account(id = 1, name = "Наличные", type = AccountType.CASH, currency = CurrencyCode.RSD, openingDate = OPENING)
    private val card = Account(id = 2, name = "Карта", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = OPENING)

    private val october = AnalyticsPeriod.Month(YearMonth.of(2026, 10))
    private val today = LocalDate.of(2026, 10, 14)

    private fun e(date: String, amount: Money, category: Long, method: Long, withRates: Boolean = true): Expense {
        val at = Instant.parse("${date}T12:00:00Z")
        return Expense.create(amount, category, method, at, ZoneOffset.UTC, if (withRates) rates else null, at)
    }

    private val expenses = listOf(
        // октябрь
        e("2026-10-01", Money(1000, CurrencyCode.EUR), 1, 1),
        e("2026-10-01", Money(50000, CurrencyCode.RSD), 2, 2), // 5 EUR, подкатегория «Кафе»
        e("2026-10-10", Money(200000, CurrencyCode.RUB), 3, 2), // 20 EUR
        e("2026-10-12", Money(700, CurrencyCode.EUR), 2, 1, withRates = false), // без курса
        // сентябрь
        e("2026-09-05", Money(1000, CurrencyCode.EUR), 1, 1),
        e("2026-09-20", Money(3000, CurrencyCode.EUR), 3, 2),
        // вне обоих периодов
        e("2026-08-31", Money(99900, CurrencyCode.EUR), 1, 1),
    )

    private fun report(currency: CurrencyCode = CurrencyCode.EUR, period: AnalyticsPeriod = october, now: LocalDate = today) =
        Analytics.build(
            expenses, period, currency,
            listOf(food, cafe, transport).associateBy { it.id },
            listOf(cash, card).associateBy { it.id },
            now,
        )

    private fun eur(minor: Long) = Money(minor, CurrencyCode.EUR)

    @Test
    fun `totals and comparison to the same day of previous month`() {
        val r = report()
        assertEquals(eur(4200), r.total.total) // 10 + 5 + 20 + 7 (без курса, но в EUR известна)
        assertEquals(0, r.total.withoutRates)
        assertEquals(eur(4000), r.previousTotal.total)
        assertEquals(eur(1000), r.previousToDate) // сентябрь по 14-е включительно
        assertEquals(320, r.changePercent)
        assertEquals(eur(300), r.averagePerDay) // 42 € / 14 дней
    }

    @Test
    fun `year comparison uses the same calendar date despite leap year`() {
        val data = listOf(e("2027-03-01", eur(100), 1, 1), e("2027-03-02", eur(10000), 1, 1))
        fun toDate(period: AnalyticsPeriod, now: String) =
            Analytics.build(data, period, CurrencyCode.EUR, emptyMap(), emptyMap(), LocalDate.parse(now)).previousToDate
        // 1 марта 2028 (после 29 февраля) → по 1 марта 2027, а не по 2-е.
        assertEquals(eur(100), toDate(AnalyticsPeriod.Year(2028), "2028-03-01"))
        // Обратный случай: 31 декабря 2025 → по 31 декабря 2024 (раньше терялся последний день).
        val dec31 = listOf(e("2024-12-31", eur(500), 1, 1))
        assertEquals(eur(500), Analytics.build(dec31, AnalyticsPeriod.Year(2025), CurrencyCode.EUR, emptyMap(), emptyMap(), LocalDate.parse("2025-12-31")).previousToDate)
    }

    @Test
    fun `finished period compares with whole previous period`() {
        val r = report(now = LocalDate.of(2026, 11, 3))
        assertNull(r.previousToDate)
        assertEquals(eur(4000), r.comparisonBase)
        assertEquals(5, r.changePercent) // 42 vs 40
        assertEquals(eur(135), r.averagePerDay) // 42 € / 31 день = 1.3548…
    }

    @Test
    fun `display currency excludes expenses without known amount`() {
        val r = report(CurrencyCode.RSD)
        assertEquals(Money(350000, CurrencyCode.RSD), r.total.total)
        assertEquals(1, r.total.withoutRates)
    }

    @Test
    fun `daily buckets include empty days`() {
        val r = report()
        assertEquals(31, r.buckets.size)
        assertEquals(eur(1500), r.buckets[0].amount)
        assertEquals(eur(0), r.buckets[1].amount)
        assertEquals(eur(2000), r.buckets[9].amount)
        assertEquals(eur(700), r.buckets[11].amount)
    }

    @Test
    fun `cumulative current stops today, previous is full`() {
        val r = report()
        assertEquals(14, r.cumulative.size)
        assertEquals(eur(4200), r.cumulative.last())
        assertEquals(eur(1500), r.cumulative.first())
        assertEquals(30, r.previousCumulative.size)
        assertEquals(eur(4000), r.previousCumulative.last())
    }

    @Test
    fun `monthly buckets for a year`() {
        val r = report(period = AnalyticsPeriod.Year(2026))
        assertEquals(12, r.buckets.size)
        assertEquals(eur(4200), r.buckets[9].amount)
        assertEquals(eur(4000), r.buckets[8].amount)
        assertEquals(eur(99900), r.buckets[7].amount)
    }

    @Test
    fun `subcategories roll up into parent and slices are sorted`() {
        val slices = report().categories
        assertEquals(listOf(food, transport), slices.map { it.category })
        assertEquals(eur(2200), slices[0].amount) // 10 + 5 (кафе) + 7 (кафе)
        assertEquals(eur(1000), slices[0].previous)
        assertEquals(eur(3000), slices[1].previous)
        assertEquals(22f / 42f, slices[0].share, 0.0001f)
        assertEquals(1f, slices.sumOf { it.share.toDouble() }.toFloat(), 0.0001f)
    }

    @Test
    fun `payment method slices`() {
        val slices = report().accounts
        assertEquals(listOf(card, cash), slices.map { it.method })
        assertEquals(eur(2500), slices[0].amount)
        assertEquals(eur(1700), slices[1].amount)
    }

    @Test
    fun `empty period`() {
        val r = report(period = AnalyticsPeriod.Month(YearMonth.of(2027, 1)), now = today)
        assertEquals(eur(0), r.total.total)
        assertEquals(emptyList<CategorySlice>(), r.categories)
        assertNull(r.changePercent)
        assertEquals(31, r.buckets.size)
    }

    @Test
    fun `percent change rounding`() {
        assertEquals(50, Analytics.percentChange(eur(150), eur(100)))
        assertEquals(-50, Analytics.percentChange(eur(50), eur(100)))
        assertEquals(-33, Analytics.percentChange(eur(200), eur(300)))
        assertNull(Analytics.percentChange(eur(100), eur(0)))
    }
}

private val OPENING: LocalDate = LocalDate.of(2020, 1, 1)

package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AnalyticsPeriodTest {

    @Test
    fun `week starts on monday`() {
        val week = AnalyticsPeriod.weekOf(LocalDate.of(2026, 10, 14)) // среда
        assertEquals(LocalDate.of(2026, 10, 12), week.from)
        assertEquals(LocalDate.of(2026, 10, 18), week.to)
        assertEquals(LocalDate.of(2026, 10, 5), week.previous().from)
        assertEquals(AnalyticsPeriod.weekOf(LocalDate.of(2026, 10, 12)), AnalyticsPeriod.weekOf(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `month and year`() {
        val feb = AnalyticsPeriod.Month(YearMonth.of(2028, 2))
        assertEquals(29, feb.days) // високосный
        assertEquals(YearMonth.of(2028, 1), (feb.previous() as AnalyticsPeriod.Month).month)
        assertEquals(Granularity.DAY, feb.granularity)

        val year = AnalyticsPeriod.yearOf(LocalDate.of(2026, 5, 5))
        assertEquals(Granularity.MONTH, year.granularity)
        assertEquals(LocalDate.of(2025, 1, 1), year.previous().from)
    }

    @Test
    fun `custom previous has the same length right before`() {
        val p = AnalyticsPeriod.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10))
        assertEquals(10, p.days)
        assertEquals(AnalyticsPeriod.Custom(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 30)), p.previous())
        assertEquals(AnalyticsPeriod.Custom(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 20)), p.next())
    }

    @Test
    fun `long custom period is monthly`() {
        assertEquals(Granularity.DAY, AnalyticsPeriod.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 3)).granularity)
        assertEquals(Granularity.MONTH, AnalyticsPeriod.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 4)).granularity)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `week must start on monday`() {
        AnalyticsPeriod.Week(LocalDate.of(2026, 10, 14))
    }
}

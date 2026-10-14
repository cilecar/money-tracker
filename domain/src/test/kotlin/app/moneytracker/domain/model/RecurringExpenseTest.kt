package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurringExpenseTest {

    private fun template(frequency: Frequency, start: String, interval: Int = 1, end: String? = null) = RecurringExpense.create(
        amount = Money(100, CurrencyCode.EUR),
        categoryId = 1,
        paymentMethodId = 1,
        frequency = frequency,
        interval = interval,
        startDate = LocalDate.parse(start),
        endDate = end?.let(LocalDate::parse),
    )

    private fun RecurringExpense.dates(n: Int): List<LocalDate> =
        generateSequence(nextRunDate) { nextAfter(it) }.take(n).toList()

    @Test
    fun `monthly on the 31st clamps to month end but keeps the 31st`() {
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31").map(LocalDate::parse),
            template(Frequency.MONTHLY, "2026-01-31").dates(5),
        )
    }

    @Test
    fun `yearly on feb 29`() {
        assertEquals(
            listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29").map(LocalDate::parse),
            template(Frequency.YEARLY, "2028-02-29").dates(5),
        )
    }

    @Test
    fun `weekly every two weeks and daily`() {
        assertEquals(
            listOf("2026-10-14", "2026-10-28", "2026-11-11").map(LocalDate::parse),
            template(Frequency.WEEKLY, "2026-10-14", interval = 2).dates(3),
        )
        assertEquals(LocalDate.parse("2026-10-17"), template(Frequency.DAILY, "2026-10-14", interval = 3).nextAfter(LocalDate.parse("2026-10-14")))
    }

    @Test
    fun `quarterly is monthly with interval 3`() {
        assertEquals(
            listOf("2026-01-15", "2026-04-15", "2026-07-15").map(LocalDate::parse),
            template(Frequency.MONTHLY, "2026-01-15", interval = 3).dates(3),
        )
    }

    @Test
    fun `due respects end date and active flag`() {
        val t = template(Frequency.MONTHLY, "2026-10-01", end = "2026-10-31")
        assertTrue(t.isDue(LocalDate.parse("2026-10-01")))
        assertFalse(t.isDue(LocalDate.parse("2026-09-30")))
        assertFalse(t.copy(nextRunDate = LocalDate.parse("2026-11-01")).isDue(LocalDate.parse("2026-12-01")))
        assertFalse(t.copy(active = false).isDue(LocalDate.parse("2026-10-05")))
    }
}

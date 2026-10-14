package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ExpenseTest {

    private val belgrade = ZoneId.of("Europe/Belgrade")
    private val rates = RateSnapshot(BigDecimal("117.15"), BigDecimal("95.43"), LocalDate.of(2026, 10, 1))
    private val now = Instant.parse("2026-10-14T10:00:00Z")

    private fun expense(at: Instant = now, amount: Money = Money(117150, CurrencyCode.RSD)) =
        Expense.create(amount, categoryId = 1, accountId = 1, occurredAt = at, zone = belgrade, rates = rates, now = now)

    @Test
    fun `local date uses user zone`() {
        // 23:30 UTC 14 октября = 01:30 15 октября в Белграде (UTC+2 летом)
        val e = expense(at = Instant.parse("2026-10-14T23:30:00Z"))
        assertEquals(LocalDate.of(2026, 10, 15), e.localDate)
    }

    @Test
    fun `create converts with snapshot`() {
        val e = expense()
        assertEquals(1000L, e.converted!!.eurMinor)
        assertEquals(117150L, e.converted.rsdMinor)
    }

    @Test
    fun `changing date keeps rate snapshot`() {
        val e = expense()
        val moved = e.withOccurredAt(Instant.parse("2026-09-01T12:00:00Z"), belgrade, now)
        assertEquals(LocalDate.of(2026, 9, 1), moved.localDate)
        assertEquals(e.rates, moved.rates)
        assertEquals(e.converted, moved.converted)
    }

    @Test
    fun `changing amount recalculates with same snapshot`() {
        val e = expense().withAmount(Money(2000, CurrencyCode.EUR), now)
        assertEquals(rates, e.rates)
        assertEquals(234300L, e.converted!!.rsdMinor)
    }

    @Test
    fun `expense without rates can be created and filled later`() {
        val pending = Expense.create(Money(500, CurrencyCode.EUR), 1, 1, now, belgrade, rates = null, now = now)
        assertNull(pending.converted)
        assertNull(pending.withAmount(Money(600, CurrencyCode.EUR), now).converted)

        val filled = pending.withRates(rates, now)
        assertEquals(58575L, filled.converted!!.rsdMinor)
    }

    @Test
    fun `comment and tags are normalized`() {
        val e = Expense.create(
            Money(1, CurrencyCode.EUR), 1, 1, now, belgrade, rates, now,
            comment = "   ", tags = listOf(" кафе", "Кафе", "", "отпуск "),
        )
        assertNull(e.comment)
        assertEquals(listOf("кафе", "отпуск"), e.tags)
    }
}

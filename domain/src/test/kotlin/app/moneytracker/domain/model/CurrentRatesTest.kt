package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class CurrentRatesTest {

    private val d13 = LocalDate.of(2026, 10, 13)
    private val d14 = LocalDate.of(2026, 10, 14)
    private val t = Instant.parse("2026-10-14T08:00:00Z")

    private fun rate(c: CurrencyCode, v: String, date: LocalDate, s: RateSource) = ExchangeRate(date, c, BigDecimal(v), s, t)

    @Test
    fun `null when any currency is missing`() {
        assertNull(CurrentRates.resolve(emptyList(), null))
        assertNull(CurrentRates.resolve(listOf(rate(CurrencyCode.RSD, "117", d14, RateSource.API_PRIMARY)), t))
    }

    @Test
    fun `latest date wins`() {
        val current = CurrentRates.resolve(
            listOf(
                rate(CurrencyCode.RSD, "117.0", d13, RateSource.API_PRIMARY),
                rate(CurrencyCode.RSD, "117.4", d14, RateSource.API_PRIMARY),
                rate(CurrencyCode.RUB, "93.7", d14, RateSource.API_PRIMARY),
            ),
            t,
        )!!
        assertEquals(BigDecimal("117.4"), current.snapshot.rsdPerEur)
        assertEquals(d14, current.snapshot.date)
    }

    @Test
    fun `manual beats api on the same date`() {
        val current = CurrentRates.resolve(
            listOf(
                rate(CurrencyCode.RSD, "117.4", d14, RateSource.API_PRIMARY),
                rate(CurrencyCode.RSD, "116.0", d14, RateSource.MANUAL),
                rate(CurrencyCode.RUB, "93.7", d14, RateSource.API_FALLBACK),
                rate(CurrencyCode.RUB, "93.8", d14, RateSource.API_PRIMARY),
            ),
            t,
        )!!
        assertEquals(BigDecimal("116.0"), current.snapshot.rsdPerEur)
        assertTrue(current.isManual(CurrencyCode.RSD))
        assertEquals(BigDecimal("93.8"), current.snapshot.rubPerEur) // основной API важнее запасного
        assertFalse(current.isManual(CurrencyCode.RUB))
    }

    @Test
    fun `newer api replaces older manual`() {
        val current = CurrentRates.resolve(
            listOf(
                rate(CurrencyCode.RSD, "116.0", d13, RateSource.MANUAL),
                rate(CurrencyCode.RSD, "117.4", d14, RateSource.API_PRIMARY),
                rate(CurrencyCode.RUB, "93.7", d14, RateSource.API_PRIMARY),
            ),
            t,
        )!!
        assertEquals(BigDecimal("117.4"), current.snapshot.rsdPerEur)
    }

    @Test
    fun `snapshot date is the older of two`() {
        val current = CurrentRates.resolve(
            listOf(
                rate(CurrencyCode.RSD, "117.4", d14, RateSource.MANUAL),
                rate(CurrencyCode.RUB, "93.7", d13, RateSource.API_PRIMARY),
            ),
            t,
        )!!
        assertEquals(d13, current.snapshot.date)
    }
}

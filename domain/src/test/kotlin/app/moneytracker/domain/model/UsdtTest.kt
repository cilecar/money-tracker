package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class UsdtTest {

    private val date = LocalDate.of(2026, 10, 14)

    // 1 EUR = 117.15 RSD = 95.43 RUB = 1.1650 USDT
    private val rates = RateSnapshot(BigDecimal("117.15"), BigDecimal("95.43"), date, usdtPerEur = BigDecimal("1.1650"))
    private val oldRates = RateSnapshot(BigDecimal("117.15"), BigDecimal("95.43"), date)

    private fun usdt(minor: Long) = Money(minor, CurrencyCode.USDT)

    @Test
    fun `usdt converts to every display currency`() {
        assertEquals(Money(85_84, CurrencyCode.EUR), rates.convert(usdt(100_00), CurrencyCode.EUR)) // 85.8369…
        assertEquals(Money(10055_79, CurrencyCode.RSD), rates.convert(usdt(100_00), CurrencyCode.RSD)) // 10055.7939…
        assertEquals(Money(8191_42, CurrencyCode.RUB), rates.convert(usdt(100_00), CurrencyCode.RUB)) // 8191.4163…
        assertEquals(ConvertedAmounts(10055_79, 85_84, 8191_42), rates.convertAll(usdt(100_00)))
    }

    @Test
    fun `display currencies convert to usdt`() {
        assertEquals(usdt(11_65), rates.convert(Money(10_00, CurrencyCode.EUR), CurrencyCode.USDT))
        assertEquals(usdt(9_94), rates.convert(Money(1000_00, CurrencyCode.RSD), CurrencyCode.USDT)) // 9.9445…
        assertEquals(usdt(12_21), rates.convert(Money(1000_00, CurrencyCode.RUB), CurrencyCode.USDT)) // 12.2079…
    }

    @Test
    fun `cross rate usdt to rsd`() {
        assertEquals(0, BigDecimal("100.5579399141630901287553648068670").compareTo(rates.rate(CurrencyCode.USDT, CurrencyCode.RSD)))
        assertEquals(BigDecimal.ONE, rates.rate(CurrencyCode.USDT, CurrencyCode.USDT))
    }

    @Test
    fun `old snapshot without usdt cannot convert usdt but still converts the rest`() {
        assertFalse(oldRates.supports(CurrencyCode.USDT))
        assertTrue(oldRates.supports(CurrencyCode.RSD))
        assertNull(oldRates.convertOrNull(usdt(100), CurrencyCode.RSD))
        assertNull(oldRates.convertOrNull(Money(100, CurrencyCode.RSD), CurrencyCode.USDT))
        assertNull(oldRates.rateOrNull(CurrencyCode.USDT, CurrencyCode.EUR))
        assertEquals(Money(1171_50, CurrencyCode.RSD), oldRates.convertOrNull(Money(10_00, CurrencyCode.EUR), CurrencyCode.RSD))
    }

    @Test(expected = IllegalStateException::class)
    fun `converting usdt with an old snapshot fails loudly`() {
        oldRates.convert(usdt(100), CurrencyCode.EUR)
    }

    @Test
    fun `usdt operation waits for rates when the snapshot has no usdt`() {
        val now = Instant.parse("2026-10-14T10:00:00Z")
        val pending = Expense.create(usdt(5_00), 1, 1, now, ZoneOffset.UTC, oldRates, now)
        assertNull(pending.rates)
        assertNull(pending.converted)
        // Сумма в USDT известна и без курса.
        assertEquals(usdt(5_00), pending.amountIn(CurrencyCode.USDT))
        assertNull(pending.amountIn(CurrencyCode.EUR))

        val rated = Expense.create(usdt(5_00), 1, 1, now, ZoneOffset.UTC, rates, now)
        assertEquals(rates, rated.rates)
        assertEquals(Money(4_29, CurrencyCode.EUR), rated.amountIn(CurrencyCode.EUR))

        // Правка RSD-расхода со старым снапшотом на USDT: снапшот не годится, расход снова ждёт курса.
        val edited = Expense.create(Money(100_00, CurrencyCode.RSD), 1, 1, now, ZoneOffset.UTC, oldRates, now).withAmount(usdt(1_00), now)
        assertNull(edited.rates)
        assertNull(edited.converted)
    }

    @Test
    fun `usdt is an input currency but not a display one`() {
        assertTrue(CurrencyCode.USDT in CurrencyCode.entries)
        assertEquals(listOf(CurrencyCode.RSD, CurrencyCode.EUR, CurrencyCode.RUB), CurrencyCode.DISPLAY)
        assertEquals(2, CurrencyCode.USDT.fractionDigits)
        assertEquals(usdt(12_35), Money.parse("12.35", CurrencyCode.USDT))
        assertNull("больше двух знаков — ошибка ввода, а не округление", Money.parse("12.3456", CurrencyCode.USDT))
        // Ловушка общего правила разбора: ровно три цифры после единственного разделителя — это тысячи.
        assertEquals(usdt(12345_00), Money.parse("12.345", CurrencyCode.USDT))
    }

    @Test
    fun `current rates work without usdt and pick it up when present`() {
        val t = Instant.parse("2026-10-14T08:00:00Z")
        fun rate(c: CurrencyCode, v: String, d: LocalDate = date, s: RateSource = RateSource.API_PRIMARY) = ExchangeRate(d, c, BigDecimal(v), s, t)
        val base = listOf(rate(CurrencyCode.RSD, "117.15"), rate(CurrencyCode.RUB, "95.43"))

        val withoutUsdt = CurrentRates.resolve(base, t)!!
        assertNull(withoutUsdt.snapshot.usdtPerEur)
        assertFalse(CurrencyCode.USDT in withoutUsdt.effective)

        // Старый ручной курс USDT действует, но не делает дату снапшота RSD/RUB старее.
        val withUsdt = CurrentRates.resolve(base + rate(CurrencyCode.USDT, "1.16", date.minusDays(3), RateSource.MANUAL), t)!!
        assertEquals(BigDecimal("1.16"), withUsdt.snapshot.usdtPerEur)
        assertTrue(withUsdt.isManual(CurrencyCode.USDT))
        assertEquals(date, withUsdt.snapshot.date)
    }

    @Test
    fun `accounts total converts usdt balances and flags a missing usdt rate`() {
        val account = Account(id = 1, name = "Bybit", type = AccountType.CARD, currency = CurrencyCode.USDT, openingDate = date)
        val balances = listOf(AccountBalance(account, usdt(116_50)))
        assertEquals(AccountsTotal(Money(100_00, CurrencyCode.EUR), missingRates = false, exact = true), AccountsTotal.of(balances, CurrencyCode.EUR, rates))
        assertTrue(AccountsTotal.of(balances, CurrencyCode.EUR, oldRates).missingRates)
    }
}

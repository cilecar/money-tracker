package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class RateSnapshotTest {

    // 1 EUR = 117.1500 RSD = 95.4321 RUB
    private val rates = RateSnapshot(
        rsdPerEur = BigDecimal("117.1500"),
        rubPerEur = BigDecimal("95.4321"),
        date = LocalDate.of(2026, 10, 14),
    )

    @Test
    fun `same currency is identity`() {
        val m = Money(12345, CurrencyCode.RSD)
        assertEquals(m, rates.convert(m, CurrencyCode.RSD))
    }

    @Test
    fun `eur to rsd and rub`() {
        val tenEur = Money(1000, CurrencyCode.EUR)
        assertEquals(Money(117150, CurrencyCode.RSD), rates.convert(tenEur, CurrencyCode.RSD))
        assertEquals(Money(95432, CurrencyCode.RUB), rates.convert(tenEur, CurrencyCode.RUB)) // 954.321 → 954.32
    }

    @Test
    fun `rsd to eur`() {
        // 1000 RSD / 117.15 = 8.5360...
        assertEquals(Money(854, CurrencyCode.EUR), rates.convert(Money(100000, CurrencyCode.RSD), CurrencyCode.EUR))
    }

    @Test
    fun `cross rate rub to rsd rounds only once`() {
        // 1000 RUB * 117.15 / 95.4321 = 1227.5742... RSD
        assertEquals(Money(122757, CurrencyCode.RSD), rates.convert(Money(100000, CurrencyCode.RUB), CurrencyCode.RSD))
    }

    @Test
    fun `cross conversion is not worse than via rounded eur`() {
        // Через округлённый EUR получилось бы: 1000 RUB → 10.48 EUR → 1227.73 RSD (ошибка 15 пар).
        val direct = rates.convert(Money(100000, CurrencyCode.RUB), CurrencyCode.RSD)
        val viaRoundedEur = rates.convert(rates.convert(Money(100000, CurrencyCode.RUB), CurrencyCode.EUR), CurrencyCode.RSD)
        assertEquals(122757L, direct.minor)
        assertEquals(122773L, viaRoundedEur.minor)
    }

    @Test
    fun `tiny amounts round to zero or one minor unit`() {
        assertEquals(Money(0, CurrencyCode.EUR), rates.convert(Money(1, CurrencyCode.RSD), CurrencyCode.EUR))
        assertEquals(Money(1, CurrencyCode.RSD), rates.convert(Money(1, CurrencyCode.RUB), CurrencyCode.RSD))
    }

    @Test
    fun `negative amounts convert symmetrically`() {
        val pos = rates.convert(Money(100000, CurrencyCode.RUB), CurrencyCode.RSD)
        val neg = rates.convert(Money(-100000, CurrencyCode.RUB), CurrencyCode.RSD)
        assertEquals(-pos.minor, neg.minor)
    }

    @Test
    fun `large amount does not lose precision`() {
        // 10 млрд RSD — заведомо больше реальных трат, но проверяем, что Long + DECIMAL128 справляются.
        val big = Money(1_000_000_000_000L, CurrencyCode.RSD)
        val eur = rates.convert(big, CurrencyCode.EUR)
        assertEquals(BigDecimal("85360648.74"), eur.amount)
    }

    @Test
    fun `rate between currencies`() {
        assertEquals(0, BigDecimal("117.15").compareTo(rates.rate(CurrencyCode.EUR, CurrencyCode.RSD)))
        assertEquals(0, BigDecimal.ONE.compareTo(rates.rate(CurrencyCode.RUB, CurrencyCode.RUB)))
        val rubToRsd = rates.rate(CurrencyCode.RUB, CurrencyCode.RSD)
        assertEquals(BigDecimal("1.227574"), rubToRsd.setScale(6, java.math.RoundingMode.HALF_EVEN))
    }

    @Test
    fun `convertAll fills all currencies`() {
        val all = rates.convertAll(Money(1000, CurrencyCode.EUR))
        assertEquals(Money(117150, CurrencyCode.RSD), all[CurrencyCode.RSD])
        assertEquals(Money(1000, CurrencyCode.EUR), all[CurrencyCode.EUR])
        assertEquals(Money(95432, CurrencyCode.RUB), all[CurrencyCode.RUB])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero rate is rejected`() {
        RateSnapshot(BigDecimal.ZERO, BigDecimal.ONE, LocalDate.EPOCH)
    }
}

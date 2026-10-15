package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.repository.ExchangeRateRepository
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class SetManualRateUseCase @Inject constructor(
    private val rates: ExchangeRateRepository,
    private val fillPendingRates: FillPendingRatesUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(currency: CurrencyCode, perEur: BigDecimal) {
        require(currency != CurrencyCode.EUR) { "EUR rate is always 1" }
        require(perEur.signum() > 0) { "Rate must be positive" }
        require(isPlausible(currency, perEur)) { "Rate $perEur $currency/EUR is out of range" }
        rates.setManualRate(currency, perEur, LocalDate.now(clock))
        fillPendingRates()
    }

    companion object {
        /**
         * Границы «1 EUR = x» для ручного ввода. Ловят опечатку на порядок (лишняя цифра, запятая
         * не на месте): такой курс молча испортил бы все суммы, пересчитанные по нему. Границы
         * с запасом в несколько раз от реального курса; курсы из сети ими не ограничиваются.
         */
        private val PLAUSIBLE: Map<CurrencyCode, ClosedRange<BigDecimal>> = mapOf(
            CurrencyCode.RSD to BigDecimal("50")..BigDecimal("300"),
            CurrencyCode.RUB to BigDecimal("30")..BigDecimal("500"),
            // USDT привязан к доллару: 1 EUR ≈ 1.05–1.2 USDT. Граница уже, чем у остальных, —
            // ошибка на порядок здесь выглядит как 11 или 0.11.
            CurrencyCode.USDT to BigDecimal("0.8")..BigDecimal("1.5"),
        )

        /** null для EUR: его курс всегда 1. */
        fun plausibleRange(currency: CurrencyCode): ClosedRange<BigDecimal>? = PLAUSIBLE[currency]

        fun isPlausible(currency: CurrencyCode, perEur: BigDecimal): Boolean =
            plausibleRange(currency)?.contains(perEur) ?: false
    }
}

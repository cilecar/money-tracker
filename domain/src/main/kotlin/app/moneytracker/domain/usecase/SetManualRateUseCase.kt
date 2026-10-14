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
        rates.setManualRate(currency, perEur, LocalDate.now(clock))
        fillPendingRates()
    }
}

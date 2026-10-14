package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

class RefreshRatesUseCase @Inject constructor(
    private val rates: ExchangeRateRepository,
    private val fillPendingRates: FillPendingRatesUseCase,
    private val clock: Clock,
) {
    /** Принудительное обновление (кнопка, фоновая задача). */
    suspend operator fun invoke(): RefreshResult {
        val result = rates.refresh()
        if (result is RefreshResult.Success) fillPendingRates()
        return result
    }

    /**
     * Обновление при запуске приложения: только если курсов нет или они старше [maxAge].
     * @return null, если обновлять не понадобилось.
     */
    suspend fun ifStale(maxAge: Duration = DEFAULT_MAX_AGE): RefreshResult? {
        val lastSync = rates.current()?.lastSyncAt
        val fresh = lastSync != null && Duration.between(lastSync, clock.instant()) < maxAge
        return if (fresh) null else invoke()
    }

    companion object {
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(12)
    }
}

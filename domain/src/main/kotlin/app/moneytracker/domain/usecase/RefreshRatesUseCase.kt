package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton + Mutex: запуск приложения и фоновая задача могут одновременно решить, что курсы
 * устарели. Под замком второй вызов дождётся первого и увидит свежие курсы, а не скачает их ещё раз.
 */
@Singleton
class RefreshRatesUseCase @Inject constructor(
    private val rates: ExchangeRateRepository,
    private val fillPendingRates: FillPendingRatesUseCase,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Принудительное обновление (кнопка «обновить»). */
    suspend operator fun invoke(): RefreshResult = mutex.withLock { refresh() }

    /**
     * Обновление при запуске приложения: только если курсов нет или они старше [maxAge].
     * @return null, если обновлять не понадобилось.
     */
    suspend fun ifStale(maxAge: Duration = DEFAULT_MAX_AGE): RefreshResult? = mutex.withLock {
        val lastSync = rates.current()?.lastSyncAt
        val fresh = lastSync != null && Duration.between(lastSync, clock.instant()) < maxAge
        if (fresh) null else refresh()
    }

    private suspend fun refresh(): RefreshResult {
        val result = rates.refresh()
        if (result is RefreshResult.Success) fillPendingRates()
        return result
    }

    companion object {
        val DEFAULT_MAX_AGE: Duration = Duration.ofHours(12)
    }
}

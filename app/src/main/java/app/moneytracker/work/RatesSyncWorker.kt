package app.moneytracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.usecase.RefreshRatesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RatesSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val refreshRates: RefreshRatesUseCase,
) : CoroutineWorker(context, params) {

    // ifStale: если приложение уже загрузило курсы (при запуске или кнопкой), повторно не качаем.
    override suspend fun doWork(): Result = resultFor(refreshRates.ifStale(), runAttemptCount)

    companion object {
        /** Всего попыток, считая первую: 15 + 30 + 60 минут backoff между ними. */
        private const val MAX_ATTEMPTS = 4

        internal fun resultFor(refresh: RefreshResult?, runAttemptCount: Int): Result = when (refresh) {
            null, is RefreshResult.Success -> Result.success()
            is RefreshResult.Failure -> when (refresh.reason) {
                RefreshResult.Failure.Reason.NETWORK -> retryOrFail(runAttemptCount, MAX_ATTEMPTS)
                // Сеть есть, но источники отвечают мусором — повтор через минуты не поможет.
                RefreshResult.Failure.Reason.BAD_RESPONSE -> Result.failure()
            }
        }
    }
}

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

    override suspend fun doWork(): Result = when (refreshRates()) {
        is RefreshResult.Success -> Result.success()
        // retry() — WorkManager повторит с экспоненциальной задержкой. Ограничиваем число
        // попыток: для периодической задачи failure() не отменяет её, следующий запуск будет завтра.
        is RefreshResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val MAX_ATTEMPTS = 4
    }
}

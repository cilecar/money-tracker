package app.moneytracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.moneytracker.domain.usecase.RunRecurringUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class RecurringWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runRecurring: RunRecurringUseCase,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runOrRetry(runAttemptCount) { runRecurring() }

    companion object {
        private const val MAX_ATTEMPTS = 4

        /**
         * База занята (импорт, миграция) или диск полон — повторим с backoff через минуты,
         * а не через 12 часов до следующего периода.
         */
        internal suspend fun runOrRetry(runAttemptCount: Int, block: suspend () -> Unit): Result = try {
            block()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            retryOrFail(runAttemptCount, MAX_ATTEMPTS)
        }
    }
}

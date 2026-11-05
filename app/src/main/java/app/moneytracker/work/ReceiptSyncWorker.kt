package app.moneytracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import app.moneytracker.domain.repository.ReceiptRepository
import app.moneytracker.domain.usecase.LoadOutcome
import app.moneytracker.domain.usecase.LoadReceiptUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Догружает ожидающие чеки, когда появилась сеть. */
@HiltWorker
class ReceiptSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val receipts: ReceiptRepository,
    private val load: LoadReceiptUseCase,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = syncAll(receipts.getPendingIds(), runAttemptCount) { load(it) }

    companion object {
        /** Всего попыток, считая первую; дальше — при следующем скане или запуске приложения. */
        private const val MAX_ATTEMPTS = 5

        /** Ошибка одного чека (FAILED) не мешает остальным; повтор — только если не было сети. */
        internal suspend fun syncAll(pendingIds: List<Long>, runAttemptCount: Int, load: suspend (Long) -> LoadOutcome): Result {
            var offline = false
            for (id in pendingIds) if (load(id) == LoadOutcome.UNAVAILABLE) offline = true
            return if (offline) retryOrFail(runAttemptCount, MAX_ATTEMPTS) else Result.success()
        }
    }
}

package app.moneytracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.moneytracker.domain.usecase.RunRecurringUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecurringWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runRecurring: RunRecurringUseCase,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runRecurring()
        return Result.success()
    }
}

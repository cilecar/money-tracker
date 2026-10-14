package app.moneytracker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RatesSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Раз в сутки. KEEP: повторный вызов при каждом старте приложения не сбрасывает расписание. */
    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<RatesSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Разовая загрузка, как только появится сеть. Используем, когда при запуске
     * сети не было: не надо ждать суточного запуска.
     */
    fun syncWhenOnline() {
        val request = OneTimeWorkRequestBuilder<RatesSyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Регулярные расходы — раз в 12 часов без требований к сети: так дата не «проскочит»,
     * даже если один запуск система отложила. Повторный запуск ничего не дублирует.
     */
    fun scheduleRecurring() {
        val request = PeriodicWorkRequestBuilder<RecurringWorker>(12, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(RECURRING_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private companion object {
        const val RECURRING_WORK = "recurring-periodic"
        const val PERIODIC_WORK = "rates-sync-periodic"
        const val ONE_TIME_WORK = "rates-sync-once"
    }
}

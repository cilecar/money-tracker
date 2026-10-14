package app.moneytracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.moneytracker.notifications.BudgetNotifications
import app.moneytracker.widget.WidgetUpdater
import app.moneytracker.work.RatesSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Configuration.Provider: WorkManager создаётся по требованию с фабрикой Hilt,
 * чтобы в воркеры можно было инжектить зависимости. Стандартный инициализатор
 * отключён в манифесте.
 */
@HiltAndroidApp
class MoneyTrackerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var ratesSyncScheduler: RatesSyncScheduler
    @Inject lateinit var budgetNotifications: BudgetNotifications
    @Inject lateinit var widgetUpdater: WidgetUpdater

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        ratesSyncScheduler.schedulePeriodic()
        ratesSyncScheduler.scheduleRecurring()
        budgetNotifications.createChannel()
        widgetUpdater.start()
    }
}

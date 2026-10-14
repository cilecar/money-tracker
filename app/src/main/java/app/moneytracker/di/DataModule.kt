package app.moneytracker.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.data.db.dao.BudgetDao
import app.moneytracker.data.db.dao.CategoryDao
import app.moneytracker.data.db.dao.ExchangeRateDao
import app.moneytracker.data.db.dao.ExpenseDao
import app.moneytracker.data.db.dao.PaymentMethodDao
import app.moneytracker.data.db.dao.RecurringDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addCallback(DatabaseSeeder(context))
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Provides fun expenseDao(db: AppDatabase): ExpenseDao = db.expenseDao()
    @Provides fun categoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun paymentMethodDao(db: AppDatabase): PaymentMethodDao = db.paymentMethodDao()
    @Provides fun exchangeRateDao(db: AppDatabase): ExchangeRateDao = db.exchangeRateDao()
    @Provides fun budgetDao(db: AppDatabase): BudgetDao = db.budgetDao()
    @Provides fun recurringDao(db: AppDatabase): RecurringDao = db.recurringDao()

    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // Файл повреждён (прерванная запись) — начинаем с настроек по умолчанию, а не падаем при запуске.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) { context.preferencesDataStoreFile("settings") }

    /**
     * Clock через DI: в тестах подменяем на фиксированное время.
     * Не синглтон — чтобы смена часового пояса в системе подхватывалась без перезапуска.
     */
    @Provides
    fun clock(): Clock = Clock.systemDefaultZone()
}

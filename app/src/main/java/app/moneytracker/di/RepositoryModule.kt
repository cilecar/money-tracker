package app.moneytracker.di

import app.moneytracker.data.prefs.SettingsRepositoryImpl
import app.moneytracker.data.io.DataTransferImpl
import app.moneytracker.data.repository.BudgetRepositoryImpl
import app.moneytracker.data.repository.CategoryRepositoryImpl
import app.moneytracker.data.repository.ExchangeRateRepositoryImpl
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.data.repository.AccountRepositoryImpl
import app.moneytracker.data.repository.RecurringRepositoryImpl
import app.moneytracker.domain.repository.BudgetRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.DataTransfer
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.RecurringRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.BudgetNotifier
import app.moneytracker.notifications.BudgetNotifications
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface RepositoryModule {
    @Binds @Singleton fun expenses(impl: ExpenseRepositoryImpl): ExpenseRepository
    @Binds @Singleton fun categories(impl: CategoryRepositoryImpl): CategoryRepository
    @Binds @Singleton fun accounts(impl: AccountRepositoryImpl): AccountRepository
    @Binds @Singleton fun exchangeRates(impl: ExchangeRateRepositoryImpl): ExchangeRateRepository
    @Binds @Singleton fun budgets(impl: BudgetRepositoryImpl): BudgetRepository
    @Binds @Singleton fun recurring(impl: RecurringRepositoryImpl): RecurringRepository
    @Binds fun dataTransfer(impl: DataTransferImpl): DataTransfer
    @Binds fun budgetNotifier(impl: BudgetNotifications): BudgetNotifier
    @Binds @Singleton fun settings(impl: SettingsRepositoryImpl): SettingsRepository
}

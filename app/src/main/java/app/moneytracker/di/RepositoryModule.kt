package app.moneytracker.di

import app.moneytracker.data.prefs.SettingsRepositoryImpl
import app.moneytracker.data.io.DataTransferImpl
import app.moneytracker.data.repository.BudgetRepositoryImpl
import app.moneytracker.data.repository.CategoryRepositoryImpl
import app.moneytracker.data.repository.ExchangeRateRepositoryImpl
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.data.repository.AccountRepositoryImpl
import app.moneytracker.data.repository.IncomeCategoryRepositoryImpl
import app.moneytracker.data.repository.IncomeRepositoryImpl
import app.moneytracker.data.repository.RefundRepositoryImpl
import app.moneytracker.data.repository.TransferRepositoryImpl
import app.moneytracker.data.repository.CaptureRepositoryImpl
import app.moneytracker.domain.capture.GoogleWalletParser
import app.moneytracker.domain.capture.NotificationParser
import app.moneytracker.domain.repository.CaptureRepository
import dagger.multibindings.IntoSet
import app.moneytracker.data.repository.RecurringRepositoryImpl
import app.moneytracker.domain.repository.BudgetRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.DataTransfer
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import app.moneytracker.domain.repository.TransferRepository
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
    @Binds @Singleton fun incomes(impl: IncomeRepositoryImpl): IncomeRepository
    @Binds @Singleton fun refunds(impl: RefundRepositoryImpl): RefundRepository
    @Binds @Singleton fun incomeCategories(impl: IncomeCategoryRepositoryImpl): IncomeCategoryRepository
    @Binds @Singleton fun transfers(impl: TransferRepositoryImpl): TransferRepository
    @Binds @Singleton fun captures(impl: CaptureRepositoryImpl): CaptureRepository
    @Binds @IntoSet fun walletParser(impl: GoogleWalletParser): NotificationParser
    @Binds @Singleton fun exchangeRates(impl: ExchangeRateRepositoryImpl): ExchangeRateRepository
    @Binds @Singleton fun budgets(impl: BudgetRepositoryImpl): BudgetRepository
    @Binds @Singleton fun recurring(impl: RecurringRepositoryImpl): RecurringRepository
    @Binds fun dataTransfer(impl: DataTransferImpl): DataTransfer
    @Binds fun budgetNotifier(impl: BudgetNotifications): BudgetNotifier
    @Binds @Singleton fun settings(impl: SettingsRepositoryImpl): SettingsRepository
}

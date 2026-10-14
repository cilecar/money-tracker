package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Analytics
import app.moneytracker.domain.model.AnalyticsPeriod
import app.moneytracker.domain.model.AnalyticsReport
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class ObserveAnalyticsUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    /** Один запрос на оба периода: прошлый и текущий идут подряд, диапазон непрерывный. */
    operator fun invoke(period: AnalyticsPeriod, currency: CurrencyCode): Flow<AnalyticsReport> = combine(
        expenses.observe(ExpenseFilter(from = period.previous().from, to = period.to)),
        categories.observeAll(includeArchived = true),
        accounts.observeAll(includeArchived = true),
    ) { list, cats, methods ->
        Analytics.build(
            expenses = list,
            period = period,
            currency = currency,
            categories = cats.associateBy { it.id },
            accounts = methods.associateBy { it.id },
            today = LocalDate.now(clock),
        )
    }
}

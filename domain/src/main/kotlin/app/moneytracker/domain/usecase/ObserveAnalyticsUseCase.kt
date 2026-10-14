package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Analytics
import app.moneytracker.domain.model.AnalyticsPeriod
import app.moneytracker.domain.model.AnalyticsReport
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class ObserveAnalyticsUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val incomes: IncomeRepository,
    private val refunds: RefundRepository,
    private val incomeCategories: IncomeCategoryRepository,
    private val clock: Clock,
) {
    /** Один запрос на оба периода: прошлый и текущий идут подряд, диапазон непрерывный. */
    operator fun invoke(period: AnalyticsPeriod, currency: CurrencyCode): Flow<AnalyticsReport> {
        val from = period.previous().from
        val operations = combine(
            expenses.observe(ExpenseFilter(from = from, to = period.to)),
            refunds.observeBetween(from, period.to),
            incomes.observeBetween(from, period.to),
        ) { e, r, i -> Triple(e, r, i) }
        val references = combine(
            categories.observeAll(includeArchived = true),
            accounts.observeAll(includeArchived = true),
            incomeCategories.observeAll(includeArchived = true),
        ) { c, a, ic -> Triple(c, a, ic) }
        return combine(operations, references) { (list, refundList, incomeList), (cats, methods, incomeCats) ->
            Analytics.build(
                expenses = list,
                period = period,
                currency = currency,
                categories = cats.associateBy { it.id },
                accounts = methods.associateBy { it.id },
                today = LocalDate.now(clock),
                refunds = refundList,
                incomes = incomeList,
                incomeCategories = incomeCats.associateBy { it.id },
            )
        }
    }
}

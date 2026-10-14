package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.MonthSummary
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** «Осталось до конца месяца» для текущего месяца. */
class ObserveMonthSummaryUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val incomes: IncomeRepository,
    private val refunds: RefundRepository,
    private val clock: Clock,
) {
    operator fun invoke(currency: CurrencyCode): Flow<MonthSummary> {
        val today = LocalDate.now(clock)
        val month = YearMonth.from(today)
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        return combine(
            expenses.observeBetween(from, to),
            refunds.observeBetween(from, to),
            incomes.observeBetween(from, to),
        ) { e, r, i -> MonthSummary.of(month, today, currency, e, r, i) }
    }
}

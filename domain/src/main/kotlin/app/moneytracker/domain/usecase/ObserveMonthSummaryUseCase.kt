package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.MonthSummary
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes

/**
 * Сегодняшняя дата, пока поток собирают: новая выдаётся, когда дата сменилась.
 * Сверяемся с часами раз в минуту, а не одним delay до полуночи: на Android delay идёт по uptime,
 * который во сне устройства стоит, — полночь «наступила бы» на часы позже.
 */
fun Clock.observeToday(): Flow<LocalDate> = flow {
    while (true) {
        emit(LocalDate.now(this@observeToday))
        delay(1.minutes)
    }
}.distinctUntilChanged()

/** «Осталось до конца месяца» для текущего месяца; после полуночи пересчитывается сам. */
class ObserveMonthSummaryUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val incomes: IncomeRepository,
    private val refunds: RefundRepository,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(currency: CurrencyCode): Flow<MonthSummary> = clock.observeToday().flatMapLatest { today ->
        val month = YearMonth.from(today)
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        combine(
            expenses.observeBetween(from, to),
            refunds.observeBetween(from, to),
            incomes.observeBetween(from, to),
        ) { e, r, i -> MonthSummary.of(month, today, currency, e, r, i) }
    }
}

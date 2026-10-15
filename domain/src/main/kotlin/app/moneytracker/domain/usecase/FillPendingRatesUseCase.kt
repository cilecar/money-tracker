package app.moneytracker.domain.usecase

import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import java.time.Clock
import javax.inject.Inject

/**
 * Проставляет снапшот курсов расходам, которые сохранили без курсов.
 * Берём текущие курсы — других у нас на тот момент и не было.
 */
class FillPendingRatesUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val incomes: IncomeRepository,
    private val refunds: RefundRepository,
    private val rates: ExchangeRateRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    /** @return сколько операций (расходов, доходов, возвратов) обновлено. */
    suspend operator fun invoke(): Int {
        val current = rates.current() ?: return 0
        val now = clock.instant()
        val snapshot = current.snapshot
        // Не update(): тот перезаписал бы весь расход копией, прочитанной до правок пользователя.
        // Операции в USDT ждут, пока в текущих курсах не появится USDT.
        val filled = expenses.getWithoutRates().filter { snapshot.supports(it.amount.currency) }
            .count { expenses.fillRates(it.withRates(snapshot, now)) } +
            refunds.getWithoutRates().filter { snapshot.supports(it.amount.currency) }
                .count { refunds.fillRates(it.withRates(snapshot, now)) }
        val filledIncomes = incomes.getWithoutRates().filter { snapshot.supports(it.amount.currency) }
            .count { incomes.fillRates(it.withRates(snapshot, now)) }
        // Расходы в другой валюте теперь учитываются в бюджетах — пороги могли пересечься.
        if (filled > 0) checkBudgets()
        return filled + filledIncomes
    }
}

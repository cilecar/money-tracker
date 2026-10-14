package app.moneytracker.domain.usecase

import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import java.time.Clock
import javax.inject.Inject

/**
 * Проставляет снапшот курсов расходам, которые сохранили без курсов.
 * Берём текущие курсы — других у нас на тот момент и не было.
 */
class FillPendingRatesUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val rates: ExchangeRateRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    /** @return сколько расходов обновлено. */
    suspend operator fun invoke(): Int {
        val current = rates.current() ?: return 0
        val pending = expenses.getWithoutRates()
        val now = clock.instant()
        pending.forEach { expenses.update(it.withRates(current.snapshot, now)) }
        // Расходы в другой валюте теперь учитываются в бюджетах — пороги могли пересечься.
        if (pending.isNotEmpty()) checkBudgets()
        return pending.size
    }
}

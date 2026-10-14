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
        val now = clock.instant()
        // Не update(): тот перезаписал бы весь расход копией, прочитанной до правок пользователя.
        val filled = expenses.getWithoutRates().count { expenses.fillRates(it.withRates(current.snapshot, now)) }
        // Расходы в другой валюте теперь учитываются в бюджетах — пороги могли пересечься.
        if (filled > 0) checkBudgets()
        return filled
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DebtSummary
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.PersonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** Сводка открытых долгов для экрана «Долги»; пересчитывается при новых курсах. */
class ObserveDebtsUseCase @Inject constructor(
    private val debts: DebtRepository,
    private val persons: PersonRepository,
    private val rates: ExchangeRateRepository,
) {
    operator fun invoke(currency: CurrencyCode): Flow<DebtSummary> = combine(
        debts.observeDebts(),
        // Архивный человек с открытым долгом всё равно должен быть виден.
        persons.observeAll(includeArchived = true),
        rates.observeCurrent(),
    ) { list, people, current -> DebtSummary.of(list, people.associateBy { it.id }, currency, current?.snapshot) }
}

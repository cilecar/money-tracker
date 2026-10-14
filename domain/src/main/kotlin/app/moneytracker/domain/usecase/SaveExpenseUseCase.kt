package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.SettingsRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** То, что пользователь ввёл на экране добавления/редактирования. */
data class ExpenseInput(
    /** 0 — новый расход. */
    val id: Long = 0,
    val amount: Money,
    val categoryId: Long,
    val accountId: Long,
    val occurredAt: Instant,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
)

class SaveExpenseUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val rates: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: ExpenseInput): Long {
        require(input.amount.minor > 0) { "Amount must be positive" }
        val now = clock.instant()
        val zone = clock.zone

        val id = if (input.id == 0L) {
            // Новый расход: фиксируем текущие курсы (или null, если их ещё нет).
            expenses.insert(
                Expense.create(
                    amount = input.amount,
                    categoryId = input.categoryId,
                    accountId = input.accountId,
                    occurredAt = input.occurredAt,
                    zone = zone,
                    rates = rates.current()?.snapshot,
                    now = now,
                    comment = input.comment,
                    tags = input.tags,
                ),
            )
        } else {
            val old = requireNotNull(expenses.get(input.id)) { "Expense ${input.id} not found" }
            // Редактирование: снапшот курса остаётся прежним при любых изменениях.
            var updated = old
            if (input.occurredAt != old.occurredAt) updated = updated.withOccurredAt(input.occurredAt, zone, now)
            if (input.amount != old.amount) updated = updated.withAmount(input.amount, now)
            updated = updated.copy(
                categoryId = input.categoryId,
                accountId = input.accountId,
                // Сумма списания относится к прежнему счёту.
                accountAmountMinor = updated.accountAmountMinor.takeIf { input.accountId == old.accountId },
                comment = input.comment?.trim()?.takeIf { it.isNotEmpty() },
                tags = Expense.normalizeTags(input.tags),
                updatedAt = now,
            )
            expenses.update(updated)
            old.id
        }
        // Следующий расход начнём с той же валюты.
        settings.setInputCurrency(input.amount.currency)
        checkBudgets()
        return id
    }
}

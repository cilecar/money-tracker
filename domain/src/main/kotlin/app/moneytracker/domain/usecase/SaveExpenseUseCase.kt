package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseShare
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.ShareInput
import app.moneytracker.domain.model.validateShares
import app.moneytracker.domain.repository.DebtRepository
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
    /**
     * Доли «мне должны», включая погашенные (их — без изменений). null — доли не менялись:
     * сохранённые всё равно проверяются против новой суммы и валюты.
     */
    val shares: List<ShareInput>? = null,
)

class SaveExpenseUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val debts: DebtRepository,
    private val rates: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: ExpenseInput): Long {
        require(input.amount.minor > 0) { "Amount must be positive" }
        val now = clock.instant()
        val zone = clock.zone

        // Расход могли удалить, пока был открыт редактор: тогда сохраняем его как новый — правки не теряем.
        val old = if (input.id != 0L) expenses.get(input.id) else null
        val newShares = sharesToSave(input, old)
        val expense = if (old == null) {
            // Новый расход: фиксируем текущие курсы (или null, если их ещё нет).
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
            )
        } else {
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
            updated
        }
        val id = expenses.save(
            expense,
            newShares?.map { ExpenseShare(expenseId = expense.id, personId = it.personId, amount = it.amount, createdAt = now, updatedAt = now) },
        )
        // Следующий расход начнём с той же валюты.
        settings.setInputCurrency(input.amount.currency)
        checkBudgets()
        return id
    }

    /**
     * Проверяет доли против суммы и валюты расхода. @return неуплаченные доли для замены или null — не трогать.
     * Погашенную долю менять нельзя: баланс счёта уже учёл пришедшие за неё деньги.
     */
    private suspend fun sharesToSave(input: ExpenseInput, old: Expense?): List<ShareInput>? {
        val existing = if (old != null) debts.getSharesForExpense(old.id) else emptyList()
        val settled = existing.filter { it.isSettled }
        val all = input.shares ?: existing.map { ShareInput(it.personId, it.amount) }
        if (input.shares != null) {
            for (s in settled) {
                check(input.shares.any { it.personId == s.personId && it.amount == s.amount }) { "Settled share ${s.id} cannot be changed" }
            }
        }
        validateShares(input.amount, all)?.let { throw IllegalArgumentException("Invalid shares: $it") }
        return input.shares?.filter { i -> settled.none { it.personId == i.personId } }
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.model.cleanComment
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class IncomeInput(
    /** 0 — новый доход. */
    val id: Long = 0,
    val amount: Money,
    val accountId: Long,
    val categoryId: Long,
    val occurredAt: Instant,
    val comment: String? = null,
)

/** Снапшот курса — как у расходов: фиксируется при создании, при правках не меняется. */
class SaveIncomeUseCase @Inject constructor(
    private val incomes: IncomeRepository,
    private val rates: ExchangeRateRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: IncomeInput): Long {
        require(input.amount.minor > 0) { "Amount must be positive" }
        val now = clock.instant()
        if (input.id == 0L) {
            return incomes.insert(
                Income.create(input.amount, input.accountId, input.categoryId, input.occurredAt, clock.zone, rates.current()?.snapshot, now, input.comment),
            )
        }
        val old = requireNotNull(incomes.get(input.id)) { "Income ${input.id} not found" }
        var updated = old
        if (input.occurredAt != old.occurredAt) updated = updated.withOccurredAt(input.occurredAt, clock.zone, now)
        if (input.amount != old.amount) updated = updated.withAmount(input.amount, now)
        incomes.update(
            updated.copy(
                accountId = input.accountId,
                categoryId = input.categoryId,
                comment = cleanComment(input.comment),
                accountAmountMinor = updated.accountAmountMinor.takeIf { input.accountId == old.accountId },
                updatedAt = now,
            ),
        )
        return old.id
    }
}

data class RefundInput(
    val id: Long = 0,
    val amount: Money,
    val accountId: Long,
    /** Исходный расход: категория берётся из него. */
    val expenseId: Long? = null,
    /** Для возврата без расхода — категория, траты которой он уменьшает. */
    val categoryId: Long? = null,
    val occurredAt: Instant,
    val comment: String? = null,
)

class SaveRefundUseCase @Inject constructor(
    private val refunds: RefundRepository,
    private val expenses: ExpenseRepository,
    private val rates: ExchangeRateRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: RefundInput): Long {
        require(input.amount.minor > 0) { "Amount must be positive" }
        val now = clock.instant()
        val old = if (input.id != 0L) requireNotNull(refunds.get(input.id)) { "Refund ${input.id} not found" } else null
        // Связь с расходом при правке не меняется; если расход удалили, категория остаётся прежней.
        val expenseId = old?.expenseId ?: input.expenseId
        val categoryId = expenseId?.let { expenses.get(it)?.categoryId } ?: old?.categoryId ?: requireNotNull(input.categoryId) {
            "Refund needs an expense or a category"
        }
        val id = if (old == null) {
            refunds.insert(
                Refund.create(input.amount, input.accountId, expenseId, categoryId, input.occurredAt, clock.zone, rates.current()?.snapshot, now, input.comment),
            )
        } else {
            var updated = old
            if (input.occurredAt != old.occurredAt) updated = updated.withOccurredAt(input.occurredAt, clock.zone, now)
            if (input.amount != old.amount) updated = updated.withAmount(input.amount, now)
            refunds.update(
                updated.copy(
                    accountId = input.accountId,
                    categoryId = if (old.expenseId == null) input.categoryId ?: old.categoryId else categoryId,
                    comment = cleanComment(input.comment),
                    accountAmountMinor = updated.accountAmountMinor.takeIf { input.accountId == old.accountId },
                    updatedAt = now,
                ),
            )
            old.id
        }
        // Возврат уменьшает траты категории — бюджет мог вернуться ниже порога (уведомлений это не шлёт).
        checkBudgets()
        return id
    }
}

class DeleteRefundUseCase @Inject constructor(
    private val refunds: RefundRepository,
    private val checkBudgets: CheckBudgetsUseCase,
) {
    /** @return удалённый возврат — для «Отменить». */
    suspend operator fun invoke(id: Long): Refund? {
        val refund = refunds.get(id) ?: return null
        refunds.delete(id)
        // Без возврата траты категории выросли — порог бюджета мог быть пересечён.
        checkBudgets()
        return refund
    }
}

enum class IncomeCategoryError { EMPTY_NAME, DUPLICATE_NAME, LAST_ACTIVE, NEEDS_TARGET, INVALID_TARGET }

sealed interface IncomeCategoryResult {
    data class Success(val id: Long) : IncomeCategoryResult
    data class Error(val error: IncomeCategoryError) : IncomeCategoryResult
}

class SaveIncomeCategoryUseCase @Inject constructor(private val repository: IncomeCategoryRepository) {
    suspend operator fun invoke(category: IncomeCategory): IncomeCategoryResult {
        val clean = category.copy(name = category.name.trim())
        if (clean.name.isEmpty()) return IncomeCategoryResult.Error(IncomeCategoryError.EMPTY_NAME)
        val all = repository.getAll()
        if (all.any { it.id != clean.id && !it.archived && CategoryRules.sameName(it.name, clean.name) }) {
            return IncomeCategoryResult.Error(IncomeCategoryError.DUPLICATE_NAME)
        }
        val toSave = if (clean.id == 0L) clean.copy(sortOrder = repository.nextSortOrder()) else clean
        val rowId = repository.upsert(toSave)
        return IncomeCategoryResult.Success(if (clean.id != 0L) clean.id else rowId)
    }
}

class ArchiveIncomeCategoryUseCase @Inject constructor(private val repository: IncomeCategoryRepository) {
    suspend operator fun invoke(id: Long, archived: Boolean): IncomeCategoryResult {
        val all = repository.getAll()
        val category = all.firstOrNull { it.id == id } ?: return IncomeCategoryResult.Success(id)
        if (archived && all.none { !it.archived && it.id != id }) return IncomeCategoryResult.Error(IncomeCategoryError.LAST_ACTIVE)
        if (!archived && all.any { it.id != id && !it.archived && CategoryRules.sameName(it.name, category.name) }) {
            return IncomeCategoryResult.Error(IncomeCategoryError.DUPLICATE_NAME)
        }
        repository.setArchived(id, archived)
        return IncomeCategoryResult.Success(id)
    }
}

class DeleteIncomeCategoryUseCase @Inject constructor(private val repository: IncomeCategoryRepository) {
    suspend fun usage(id: Long): Int = repository.countIncomes(id)

    suspend operator fun invoke(id: Long, moveTo: Long? = null): IncomeCategoryResult {
        val all = repository.getAll()
        val category = all.firstOrNull { it.id == id } ?: return IncomeCategoryResult.Success(id)
        if (!category.archived && all.count { !it.archived } <= 1) return IncomeCategoryResult.Error(IncomeCategoryError.LAST_ACTIVE)
        val used = repository.countIncomes(id) > 0
        if (used) {
            if (moveTo == null) return IncomeCategoryResult.Error(IncomeCategoryError.NEEDS_TARGET)
            if (all.none { it.id == moveTo && it.id != id && !it.archived }) return IncomeCategoryResult.Error(IncomeCategoryError.INVALID_TARGET)
        }
        repository.delete(id, moveTo.takeIf { used })
        return IncomeCategoryResult.Success(id)
    }
}

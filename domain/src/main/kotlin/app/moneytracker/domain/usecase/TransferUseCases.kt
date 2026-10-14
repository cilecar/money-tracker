package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Transfer
import app.moneytracker.domain.model.cleanComment
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.TransferRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class TransferInput(
    val id: Long = 0,
    val fromAccountId: Long,
    val toAccountId: Long,
    /** В валюте счёта-источника. */
    val fromAmount: Money,
    /** В валюте счёта-получателя. */
    val toAmount: Money,
    /** Комиссия в валюте источника; null или ноль — без комиссии. */
    val fee: Money? = null,
    val occurredAt: Instant,
    val comment: String? = null,
)

enum class TransferError { SAME_ACCOUNT, CURRENCY_MISMATCH, NOT_POSITIVE }

sealed interface TransferResult {
    data class Success(val id: Long) : TransferResult
    data class Error(val error: TransferError) : TransferResult
}

/**
 * Перевод хранит обе суммы как ввёл пользователь — без пересчёта по API. Комиссия записывается обычным
 * расходом в категории «Комиссии» со счёта-источника: она настоящая трата и попадает в аналитику и бюджеты.
 */
class SaveTransferUseCase @Inject constructor(
    private val transfers: TransferRepository,
    private val accounts: AccountRepository,
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val rates: ExchangeRateRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: TransferInput): TransferResult {
        if (input.fromAccountId == input.toAccountId) return TransferResult.Error(TransferError.SAME_ACCOUNT)
        if (input.fromAmount.minor <= 0 || input.toAmount.minor <= 0 || (input.fee?.minor ?: 0) < 0) {
            return TransferResult.Error(TransferError.NOT_POSITIVE)
        }
        val from = requireNotNull(accounts.get(input.fromAccountId)) { "Account ${input.fromAccountId} not found" }
        val to = requireNotNull(accounts.get(input.toAccountId)) { "Account ${input.toAccountId} not found" }
        // Суммы — в валютах счетов: иначе баланс и курс обменника не сойдутся.
        val fee = input.fee?.takeIf { it.minor > 0 }
        if (input.fromAmount.currency != from.currency || input.toAmount.currency != to.currency || (fee != null && fee.currency != from.currency)) {
            return TransferResult.Error(TransferError.CURRENCY_MISMATCH)
        }

        val now = clock.instant()
        val zone = clock.zone
        val old = if (input.id != 0L) requireNotNull(transfers.get(input.id)) { "Transfer ${input.id} not found" } else null
        val transfer = if (old == null) {
            Transfer.create(from.id, to.id, input.fromAmount, input.toAmount, input.occurredAt, zone, rates.current()?.snapshot, now, input.comment)
        } else {
            // Курс API на момент перевода не меняется при правке — как снапшот у расходов.
            var updated = old.copy(
                fromAccountId = from.id,
                toAccountId = to.id,
                fromAmount = input.fromAmount,
                toAmount = input.toAmount,
                comment = cleanComment(input.comment),
                updatedAt = now,
            )
            if (input.occurredAt != old.occurredAt) updated = updated.withOccurredAt(input.occurredAt, zone, now)
            updated
        }

        val oldFee = old?.feeExpenseId?.let { expenses.get(it) }
        val feeExpense = fee?.let { amount ->
            if (oldFee != null) {
                var e = oldFee
                if (e.amount != amount) e = e.withAmount(amount, now)
                if (e.occurredAt != transfer.occurredAt) e = e.withOccurredAt(transfer.occurredAt, zone, now)
                e.copy(accountId = from.id, comment = transfer.comment, updatedAt = now)
            } else {
                Expense.create(amount, feesCategoryId(), from.id, transfer.occurredAt, zone, transfer.apiRates ?: rates.current()?.snapshot, now, transfer.comment)
            }
        }
        val id = transfers.save(transfer, feeExpense)
        if (fee != null || oldFee != null) checkBudgets()
        return TransferResult.Success(id)
    }

    /** Категория «Комиссии» по ключу; если её удалили — создаём заново (имя в UI берётся по ключу). */
    private suspend fun feesCategoryId(): Long {
        val all = categories.getAll()
        all.firstOrNull { it.defaultKey == FEES_KEY && !it.archived }?.let { return it.id }
        all.firstOrNull { it.defaultKey == FEES_KEY }?.let { return it.id }
        return categories.upsert(
            Category(name = "Комиссии", defaultKey = FEES_KEY, iconKey = "receipt", color = 0xFF78909C.toInt(), sortOrder = categories.nextSortOrder(null)),
        )
    }

    companion object {
        const val FEES_KEY = "fees"
    }
}

class DeleteTransferUseCase @Inject constructor(
    private val transfers: TransferRepository,
    private val expenses: ExpenseRepository,
    private val checkBudgets: CheckBudgetsUseCase,
) {
    /** @return перевод и его комиссия — для «Отменить». */
    suspend operator fun invoke(id: Long): Pair<Transfer, Expense?>? {
        val transfer = transfers.get(id) ?: return null
        val fee = transfer.feeExpenseId?.let { expenses.get(it) }
        transfers.delete(id)
        if (fee != null) checkBudgets()
        return transfer to fee
    }
}

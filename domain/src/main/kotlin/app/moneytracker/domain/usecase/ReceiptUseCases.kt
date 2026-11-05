package app.moneytracker.domain.usecase

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.receipt.FetchResult
import app.moneytracker.domain.receipt.FiscalLinkParser
import app.moneytracker.domain.receipt.FiscalLinkResult
import app.moneytracker.domain.receipt.ItemNameParser
import app.moneytracker.domain.receipt.ItemToSave
import app.moneytracker.domain.receipt.ReceiptSource
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.domain.receipt.ReceiptSyncScheduler
import app.moneytracker.domain.repository.CaptureRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.ReceiptRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

sealed interface IntakeResult {
    data object NotFiscal : IntakeResult
    data object Unsupported : IntakeResult
    data class Duplicate(val receiptId: Long, val expenseId: Long?) : IntakeResult
    data class Added(val receiptId: Long, val loaded: Boolean) : IntakeResult
}

enum class LoadOutcome { LOADED, FAILED, UNAVAILABLE }

/** Загрузка чека с сайта налоговой и сохранение позиций. */
class LoadReceiptUseCase @Inject constructor(
    private val receipts: ReceiptRepository,
    private val source: ReceiptSource,
) {
    suspend operator fun invoke(receiptId: Long): LoadOutcome {
        val receipt = receipts.get(receiptId) ?: return LoadOutcome.FAILED
        return when (val r = source.fetch(receipt.url)) {
            is FetchResult.Ok -> {
                // Скидка (отрицательная сумма или количество) остаётся в чеке, но не становится товаром.
                val items = r.receipt.items.map { item ->
                    val discount = item.totalMinor < 0 || item.quantityMilli < 0
                    ItemToSave(item, if (discount) null else ItemNameParser.parse(item.rawName))
                }
                receipts.saveLoaded(receiptId, r.receipt, items)
                LoadOutcome.LOADED
            }
            is FetchResult.Invalid -> {
                receipts.markFailed(receiptId, r.reason)
                LoadOutcome.FAILED
            }
            FetchResult.Unavailable -> LoadOutcome.UNAVAILABLE
        }
    }
}

/**
 * Скан или «Поделиться»: разбор ссылки, защита от повтора, сохранение и попытка сразу загрузить.
 * Не успели за [fetchTimeout] или нет сети — чек ждёт, позиции догрузит фоновая задача.
 */
class IntakeReceiptUseCase @Inject constructor(
    private val receipts: ReceiptRepository,
    private val load: LoadReceiptUseCase,
    private val sync: ReceiptSyncScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(text: String, fetchTimeout: Duration = Duration.ofSeconds(5)): IntakeResult {
        val link = when (val parsed = FiscalLinkParser.parse(text)) {
            FiscalLinkResult.NotFiscal -> return IntakeResult.NotFiscal
            FiscalLinkResult.Unsupported -> return IntakeResult.Unsupported
            is FiscalLinkResult.Ok -> parsed.link
        }
        receipts.findByInvoice(link.invoiceNumber)?.let { return IntakeResult.Duplicate(it.id, it.expenseId) }
        val id = receipts.insertPending(link, clock.instant())
            ?: receipts.findByInvoice(link.invoiceNumber)!!.let { return IntakeResult.Duplicate(it.id, it.expenseId) }
        var outcome: LoadOutcome? = null
        try {
            outcome = withTimeoutOrNull(fetchTimeout.toMillis()) { load(id) }
        } finally {
            // Не загрузили (нет сети, таймаут или приём отменили «назад») — чек уже сохранён, догрузит фон.
            if (outcome == null || outcome == LoadOutcome.UNAVAILABLE) sync.syncReceiptsWhenOnline()
        }
        return IntakeResult.Added(id, outcome == LoadOutcome.LOADED)
    }
}

sealed interface ReceiptCandidate {
    val occurredAt: Instant

    data class OfExpense(val expense: Expense) : ReceiptCandidate {
        override val occurredAt: Instant get() = expense.occurredAt
    }

    /** Покупка из уведомления Wallet, ещё не добавленная: без неё скан и подтверждение дали бы дубль. */
    data class OfCapture(val capture: CapturedPurchase) : ReceiptCandidate {
        override val occurredAt: Instant get() = capture.occurredAt
    }
}

/** [matching] — та же сумма в RSD в тот же день; [sameDay] — все расходы дня без чека («Выбрать другой»). */
data class ReceiptCandidates(val matching: List<ReceiptCandidate>, val sameDay: List<Expense>)

class FindReceiptCandidatesUseCase @Inject constructor(
    private val expenses: ExpenseRepository,
    private val captures: CaptureRepository,
    private val receipts: ReceiptRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(receiptId: Long): ReceiptCandidates {
        val receipt = receipts.get(receiptId) ?: return ReceiptCandidates(emptyList(), emptyList())
        val total = Money(receipt.totalMinor, CurrencyCode.RSD)
        val day = receipt.issuedAt.atZone(clock.zone).toLocalDate()

        val dayExpenses = expenses.observeBetween(day, day).first()
        val taken = receipts.expensesWithReceipt(dayExpenses.map { it.id }) - setOfNotNull(receipt.expenseId)
        val free = dayExpenses.filter { it.id !in taken && it.id != receipt.expenseId }

        val from = day.atStartOfDay(clock.zone).toInstant()
        val to = day.plusDays(1).atStartOfDay(clock.zone).toInstant().minusNanos(1)
        val pending = captures.getBetween(from, to).filter { it.status == CaptureStatus.PENDING && it.amount == total }

        val matching = (free.filter { it.amount == total }.map { ReceiptCandidate.OfExpense(it) } + pending.map { ReceiptCandidate.OfCapture(it) })
            .sortedBy { Duration.between(it.occurredAt, receipt.issuedAt).abs() }
        return ReceiptCandidates(matching, free.sortedByDescending { it.occurredAt })
    }
}

class AttachReceiptUseCase @Inject constructor(private val receipts: ReceiptRepository) {
    /** [rememberCategoryId] != null — новый расход из чека: его категория становится категорией сети по умолчанию. */
    suspend operator fun invoke(receiptId: Long, expenseId: Long, rememberCategoryId: Long? = null): Boolean {
        if (!receipts.attach(receiptId, expenseId)) return false
        if (rememberCategoryId != null) {
            receipts.chainOfReceipt(receiptId)?.let { receipts.setChainDefaultCategory(it.id, rememberCategoryId) }
        }
        return true
    }
}

/** Что подставить в форму нового расхода из чека. */
data class ReceiptPrefill(val amount: Money, val occurredAt: Instant, val comment: String?, val categoryId: Long?)

class PrefillExpenseFromReceiptUseCase @Inject constructor(private val receipts: ReceiptRepository) {
    suspend operator fun invoke(receiptId: Long): ReceiptPrefill? {
        val receipt = receipts.get(receiptId) ?: return null
        val chain = if (receipt.status == ReceiptStatus.LOADED) receipts.chainOfReceipt(receiptId) else null
        return ReceiptPrefill(Money(receipt.totalMinor, CurrencyCode.RSD), receipt.issuedAt, chain?.displayName, chain?.defaultCategoryId)
    }
}

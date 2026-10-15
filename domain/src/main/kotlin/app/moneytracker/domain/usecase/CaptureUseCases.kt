package app.moneytracker.domain.usecase

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.capture.NotificationParser
import app.moneytracker.domain.capture.PurchaseDeduplicator
import app.moneytracker.domain.capture.RawNotification
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.CaptureRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import java.time.Clock
import javax.inject.Inject

/** Что подставить при добавлении: категория по правилу мерчанта и счёт по цифрам карты. */
data class CaptureSuggestion(val categoryId: Long?, val accountId: Long?) {
    /** «Добавить» в один тап — только когда известно и то, и другое; иначе только «Изменить». */
    val canAddDirectly: Boolean get() = categoryId != null && accountId != null
}

sealed interface CaptureResult {
    /** Не из белого списка или не покупка. */
    data object Ignored : CaptureResult
    data class Duplicate(val existing: CapturedPurchase) : CaptureResult
    data class Captured(val purchase: CapturedPurchase, val suggestion: CaptureSuggestion) : CaptureResult
}

class SuggestCaptureUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(purchase: CapturedPurchase): CaptureSuggestion {
        // Правило могло указывать на категорию, которую с тех пор отправили в архив.
        val categoryId = captures.ruleFor(purchase.merchantKey)
            ?.takeIf { id -> categories.getAll(includeArchived = false).any { it.id == id } }
        val accountId = purchase.cardSuffix?.let { suffix ->
            accounts.getAll(includeArchived = false).firstOrNull { it.cardSuffix == suffix }?.id
        }
        return CaptureSuggestion(categoryId, accountId)
    }
}

/**
 * Разбирает уведомление: находит покупку, отсекает дубли и кладёт её в «Ожидают подтверждения».
 * В расходы покупка не попадает, пока её не подтвердят.
 */
class CapturePurchaseUseCase @Inject constructor(
    private val parsers: Set<@JvmSuppressWildcards NotificationParser>,
    private val captures: CaptureRepository,
    private val rates: ExchangeRateRepository,
    private val suggest: SuggestCaptureUseCase,
    private val clock: Clock,
) {
    /** Пакеты, уведомления которых вообще читаем; остальные игнорируются сразу. */
    val packages: Set<String> get() = parsers.map { it.packageName }.toSet()

    suspend operator fun invoke(notification: RawNotification): CaptureResult {
        val parser = parsers.firstOrNull { it.packageName == notification.packageName } ?: return CaptureResult.Ignored
        val parsed = parser.parse(notification) ?: return CaptureResult.Ignored
        val window = PurchaseDeduplicator.WINDOW
        val recent = captures.getBetween(parsed.occurredAt.minus(window), parsed.occurredAt.plus(window))
        PurchaseDeduplicator.findDuplicate(parsed, recent, rates.current()?.snapshot)?.let { return CaptureResult.Duplicate(it) }
        val purchase = CapturedPurchase.from(parsed, clock.instant())
        val saved = purchase.copy(id = captures.insert(purchase))
        return CaptureResult.Captured(saved, suggest(saved))
    }
}

/**
 * «Добавить»: расход из захваченной покупки (мерчант — в комментарий, время — время уведомления)
 * и запоминание категории для мерчанта.
 */
class ConfirmCaptureUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val suggest: SuggestCaptureUseCase,
    private val saveExpense: SaveExpenseUseCase,
) {
    /** @return id расхода или null, если категорию или счёт определить не удалось. */
    suspend operator fun invoke(captureId: Long, categoryId: Long? = null, accountId: Long? = null): Long? {
        val purchase = captures.get(captureId) ?: return null
        // Повторное нажатие (уведомление ещё не успело закрыться) не создаёт второй расход.
        if (purchase.status == CaptureStatus.CONFIRMED) return purchase.expenseId
        val suggestion = suggest(purchase)
        val category = categoryId ?: suggestion.categoryId ?: return null
        val account = accountId ?: suggestion.accountId ?: return null
        val expenseId = saveExpense(
            ExpenseInput(amount = purchase.amount, categoryId = category, accountId = account, occurredAt = purchase.occurredAt, comment = purchase.merchant),
        )
        linkExpense(purchase, expenseId, category)
        return expenseId
    }

    /** «Изменить» → расход сохранён в редакторе: отмечаем покупку и запоминаем выбранную категорию. */
    suspend fun linkExpense(captureId: Long, expenseId: Long, categoryId: Long) {
        val purchase = captures.get(captureId) ?: return
        linkExpense(purchase, expenseId, categoryId)
    }

    private suspend fun linkExpense(purchase: CapturedPurchase, expenseId: Long, categoryId: Long) {
        captures.setStatus(purchase.id, CaptureStatus.CONFIRMED, expenseId)
        captures.saveRule(purchase.merchantKey, categoryId)
    }
}

class DismissCaptureUseCase @Inject constructor(private val captures: CaptureRepository) {
    suspend operator fun invoke(captureId: Long) = captures.setStatus(captureId, CaptureStatus.DISMISSED)
}

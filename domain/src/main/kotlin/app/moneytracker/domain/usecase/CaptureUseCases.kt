package app.moneytracker.domain.usecase

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.capture.NotificationParser
import app.moneytracker.domain.capture.ParsedPurchase
import app.moneytracker.domain.capture.PurchaseDeduplicator
import app.moneytracker.domain.capture.RawNotification
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.CaptureRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

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
 *
 * Singleton + Mutex: Wallet публикует одно уведомление повторно, и сервис разбирает оба поста
 * параллельно. Без замка оба прошли бы проверку на дубль до вставки — две покупки вместо одной.
 */
@Singleton
class CapturePurchaseUseCase @Inject constructor(
    private val parsers: Set<@JvmSuppressWildcards NotificationParser>,
    private val captures: CaptureRepository,
    private val rates: ExchangeRateRepository,
    private val suggest: SuggestCaptureUseCase,
    private val clock: Clock,
) {
    /** Пакеты, уведомления которых вообще читаем; остальные игнорируются сразу. */
    val packages: Set<String> get() = parsers.map { it.packageName }.toSet()

    private val mutex = Mutex()

    suspend operator fun invoke(notification: RawNotification): CaptureResult {
        val parser = parsers.firstOrNull { it.packageName == notification.packageName } ?: return CaptureResult.Ignored
        val parsed = parser.parse(notification) ?: return CaptureResult.Ignored
        return mutex.withLock { save(parsed) }
    }

    private suspend fun save(parsed: ParsedPurchase): CaptureResult {
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
 *
 * Singleton + Mutex: двойной тап по «Добавить», кнопка в уведомлении и на экране «Ожидают»,
 * сохранение из редактора — всё это может совпасть. Проверка статуса и создание расхода под одним
 * замком, иначе оба вызова увидят PENDING и создадут два расхода.
 */
@Singleton
class ConfirmCaptureUseCase @Inject constructor(
    private val captures: CaptureRepository,
    private val suggest: SuggestCaptureUseCase,
    private val saveExpense: SaveExpenseUseCase,
) {
    private val mutex = Mutex()

    /** @return id расхода или null, если категорию или счёт определить не удалось. */
    suspend operator fun invoke(captureId: Long, categoryId: Long? = null, accountId: Long? = null): Long? = mutex.withLock {
        val purchase = captures.get(captureId) ?: return@withLock null
        // Повторное нажатие (уведомление ещё не успело закрыться) не создаёт второй расход.
        if (purchase.status == CaptureStatus.CONFIRMED) return@withLock purchase.expenseId
        val suggestion = suggest(purchase)
        val category = categoryId ?: suggestion.categoryId ?: return@withLock null
        val account = accountId ?: suggestion.accountId ?: return@withLock null
        val expenseId = saveExpense(
            ExpenseInput(amount = purchase.amount, categoryId = category, accountId = account, occurredAt = purchase.occurredAt, comment = purchase.merchant),
        )
        linkExpense(purchase, expenseId, category)
        expenseId
    }

    /**
     * «Изменить» → сохранение в редакторе: расход из формы, покупка отмечается, категория запоминается.
     * Если покупку уже добавили (кнопкой в уведомлении, пока редактор был открыт), правится тот расход,
     * а не создаётся второй.
     */
    suspend fun saveEdited(captureId: Long, input: ExpenseInput): Long = mutex.withLock {
        val purchase = captures.get(captureId)
        val existing = purchase?.takeIf { it.status == CaptureStatus.CONFIRMED }?.expenseId
        val expenseId = saveExpense(if (input.id == 0L && existing != null) input.copy(id = existing) else input)
        if (purchase != null) linkExpense(purchase, expenseId, input.categoryId)
        expenseId
    }

    private suspend fun linkExpense(purchase: CapturedPurchase, expenseId: Long, categoryId: Long) {
        captures.setStatus(purchase.id, CaptureStatus.CONFIRMED, expenseId)
        captures.saveRule(purchase.merchantKey, categoryId)
    }
}

/** «Скрыть». Одной операцией в базе: «Добавить», нажатое почти одновременно, не будет перебито. */
class DismissCaptureUseCase @Inject constructor(private val captures: CaptureRepository) {
    suspend operator fun invoke(captureId: Long): Boolean = captures.dismissIfPending(captureId)
}

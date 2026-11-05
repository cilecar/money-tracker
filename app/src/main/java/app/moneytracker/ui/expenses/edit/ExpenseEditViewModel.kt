package app.moneytracker.ui.expenses.edit

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.repository.CaptureRepository
import app.moneytracker.domain.usecase.ConfirmCaptureUseCase
import app.moneytracker.domain.usecase.SuggestCaptureUseCase
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.PersonRepository
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.ShareError
import app.moneytracker.domain.model.validateShares
import app.moneytracker.domain.usecase.SavePersonUseCase
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.ExpenseInput
import app.moneytracker.domain.usecase.SaveExpenseUseCase
import app.moneytracker.ui.navigation.ExpenseEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import app.moneytracker.domain.usecase.AttachReceiptUseCase
import app.moneytracker.domain.usecase.PrefillExpenseFromReceiptUseCase

/** Редактируемые поля формы. Справочники и курсы в форму не входят — они приходят потоками. */
data class ExpenseForm(
    val loaded: Boolean = false,
    val amount: AmountInput = AmountInput(),
    val currency: CurrencyCode = CurrencyCode.RSD,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val dateTime: LocalDateTime,
    val comment: String = "",
    val tags: List<String> = emptyList(),
    val tagDraft: String = "",
    /** Валюта до того, как её сменил выбор счёта, — пока пользователь не тронул сумму или валюту. */
    val currencySwitchedFrom: CurrencyCode? = null,
    /** Исходный расход при редактировании. */
    val original: Expense? = null,
    /** Доли «мне должны»; погашенные — только для чтения. */
    val shares: List<ShareDraft> = emptyList(),
    /** Доли трогали — сохраняем их; иначе use case проверит сохранённые против новой суммы. */
    val sharesChanged: Boolean = false,
    /** Доли, снятые сменой валюты (в том числе автоматической — выбором счёта). */
    val stashedShares: StashedShares? = null,
    /** Комиссия перевода — её не делим (сумму ведёт перевод). */
    val isTransferFee: Boolean = false,
    /** Чек, который прикрепится при сохранении (новый расход из чека или скан в ещё не сохранённом расходе). */
    val pendingReceiptId: Long? = null,
    val saving: Boolean = false,
)

/**
 * Смена валюты расхода. Доли — в его валюте: «1000 RSD» не должны молча стать «1000 EUR», поэтому они
 * откладываются, а при возврате к той валюте (кнопка «вернуть валюту», другой счёт) возвращаются.
 */
fun ExpenseForm.withCurrency(new: CurrencyCode): ExpenseForm {
    if (new == currency) return this
    val stash = if (shares.isNotEmpty()) StashedShares(currency, shares) else stashedShares
    val restored = stash?.takeIf { it.currency == new }
    return copy(
        currency = new,
        shares = restored?.shares.orEmpty(),
        stashedShares = stash.takeIf { restored == null },
        sharesChanged = sharesChanged || shares.isNotEmpty() || restored != null,
    )
}

data class ExpenseEditUiState(
    val form: ExpenseForm,
    val isNew: Boolean,
    /** Сумма в двух других валютах; пусто, если курсов нет. */
    val conversions: List<Money>,
    /** Дата курса, по которому считаем (снапшот расхода при редактировании). */
    val ratesDate: LocalDate?,
    val categories: List<Category>,
    /** Чипы быстрого выбора: недавние + выбранная. */
    val quickCategories: List<Category>,
    val selectedCategory: Category?,
    val accounts: List<Account>,
    val tagSuggestions: List<String>,
    /** Люди для разделения: активные и уже участвующие в расходе. */
    val persons: List<Person> = emptyList(),
    /** Моя доля, если расход разделён и доли корректны. */
    val myShare: Money? = null,
    /** Ошибка в долях; [ShareError.NOT_POSITIVE] — в том числе пустая или неразборчивая сумма. */
    val shareError: ShareError? = null,
) {
    val canSave: Boolean get() = form.loaded && !form.saving && !form.amount.isEmpty &&
        form.amount.toMoney(form.currency).minor > 0 && form.categoryId != null && form.accountId != null && shareError == null

    /** Погашенная доля держит валюту расхода: деньги за неё уже пришли. */
    val currencyLocked: Boolean get() = form.shares.any { it.settled }
}

sealed interface ExpenseEditEvent {
    data object Close : ExpenseEditEvent
}

@HiltViewModel
class ExpenseEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenses: ExpenseRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    ratesRepository: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val saveExpense: SaveExpenseUseCase,
    private val clock: Clock,
    private val captures: CaptureRepository,
    private val suggestCapture: SuggestCaptureUseCase,
    private val confirmCapture: ConfirmCaptureUseCase,
    private val debts: DebtRepository,
    personRepository: PersonRepository,
    private val savePerson: SavePersonUseCase,
    private val prefillFromReceipt: PrefillExpenseFromReceiptUseCase,
    private val attachReceipt: AttachReceiptUseCase,
) : ViewModel() {

    private val expenseId = savedStateHandle.toRoute<ExpenseEditRoute>().id
    private val captureId = savedStateHandle.toRoute<ExpenseEditRoute>().captureId
    private val receiptId = savedStateHandle.toRoute<ExpenseEditRoute>().receiptId
    private val form = MutableStateFlow(ExpenseForm(dateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)))

    private val _events = Channel<ExpenseEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // С архивными: у старого расхода категория могла уйти в архив, но в форме она должна отображаться.
    private val references = combine(
        categoryRepository.observeAll(includeArchived = true),
        accountRepository.observeAll(includeArchived = true),
        expenses.observeRecentCategoryIds(QUICK_CATEGORIES),
        expenses.observeTags(),
        personRepository.observeAll(includeArchived = true),
    ) { categories, methods, recent, tags, people -> References(categories, methods, recent, tags, people) }

    val state: StateFlow<ExpenseEditUiState?> =
        combine(form, references, ratesRepository.observeCurrent()) { form, refs, current ->
            buildState(form, refs, current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            load()
            if (receiptId != 0L) form.update { if (it.loaded) it.copy(pendingReceiptId = receiptId) else it }
        }
    }

    private suspend fun load() {
        if (expenseId != 0L) {
            loadExpense(expenseId)
        } else if (captureId != 0L) {
            // Покупка из уведомления: сумма, мерчант (в комментарий) и время — из неё, категория и счёт — по подсказке.
            val p = captures.get(captureId)
            if (p == null) {
                _events.send(ExpenseEditEvent.Close)
                return
            }
            // Уже добавлена (кнопкой в уведомлении или старое «Изменить») — открываем её расход, а не новый.
            val confirmedExpense = p.expenseId?.takeIf { p.status == CaptureStatus.CONFIRMED }
            if (confirmedExpense != null) {
                loadExpense(confirmedExpense)
                return
            }
            val suggestion = suggestCapture(p)
            val s = settings.settings.first()
            val methods = accountRepository.getAll(includeArchived = false)
            val method = methods.firstOrNull { it.id == suggestion.accountId }
                ?: methods.firstOrNull { it.id == s.defaultAccountId } ?: methods.firstOrNull()
            form.value = ExpenseForm(
                loaded = true,
                amount = AmountInput.from(p.amount),
                currency = p.amount.currency,
                categoryId = suggestion.categoryId,
                accountId = method?.id,
                dateTime = LocalDateTime.ofInstant(p.occurredAt, clock.zone),
                comment = p.merchant,
            )
        } else if (receiptId != 0L && loadFromReceipt(receiptId)) {
            return
        } else {
            val s = settings.settings.first()
            val methods = accountRepository.getAll(includeArchived = false)
            val method = methods.firstOrNull { it.id == s.defaultAccountId } ?: methods.firstOrNull()
            form.update {
                it.copy(loaded = true, accountId = method?.id, currency = method?.currency ?: s.inputCurrency)
            }
        }
    }

    /** Новый расход из чека: сумма в RSD и время из чека, сеть — в комментарий, её категория по умолчанию. */
    private suspend fun loadFromReceipt(id: Long): Boolean {
        val p = prefillFromReceipt(id) ?: return false
        val s = settings.settings.first()
        val methods = accountRepository.getAll(includeArchived = false)
        val method = methods.firstOrNull { it.id == s.defaultAccountId } ?: methods.firstOrNull()
        form.value = ExpenseForm(
            loaded = true,
            amount = AmountInput.from(p.amount),
            currency = p.amount.currency,
            categoryId = p.categoryId,
            accountId = method?.id,
            dateTime = LocalDateTime.ofInstant(p.occurredAt, clock.zone),
            comment = p.comment.orEmpty(),
        )
        return true
    }

    private suspend fun loadExpense(id: Long) {
        val e = expenses.get(id)
        if (e == null) {
            _events.send(ExpenseEditEvent.Close)
            return
        }
        form.value = ExpenseForm(
            loaded = true,
            amount = AmountInput.from(e.amount),
            currency = e.amount.currency,
            categoryId = e.categoryId,
            accountId = e.accountId,
            dateTime = LocalDateTime.ofInstant(e.occurredAt, clock.zone),
            comment = e.comment.orEmpty(),
            tags = e.tags,
            original = e,
            shares = debts.getSharesForExpense(e.id).map { ShareDraft(it.personId, it.amount.toDraftText(), it.isSettled) },
            isTransferFee = expenses.isTransferFee(e.id),
        )
    }

    private fun buildState(form: ExpenseForm, refs: References, current: CurrentRates?): ExpenseEditUiState {
        // При редактировании пересчёт показываем по снапшоту самого расхода — ровно так он и сохранится.
        val snapshot: RateSnapshot? = if (form.original != null) form.original.rates else current?.snapshot
        val money = form.amount.toMoney(form.currency)
        val byId = refs.categories.associateBy { it.id }
        val activeCategories = refs.categories.filter { !it.archived }
        val selected = form.categoryId?.let(byId::get)
        val quick = (listOfNotNull(selected) + refs.recentCategoryIds.mapNotNull(byId::get).filter { !it.archived })
            .distinctBy { it.id }
            .let { if (it.size < QUICK_CATEGORIES) (it + activeCategories.filter { c -> c.parentId == null }).distinctBy { c -> c.id } else it }
            .take(QUICK_CATEGORIES)
        val draft = form.tagDraft.trim().lowercase()
        val shareInputs = form.shares.toInputs(form.currency)
        val shareError = when {
            form.shares.isEmpty() -> null
            shareInputs == null -> ShareError.NOT_POSITIVE
            else -> validateShares(money, shareInputs)
        }
        val inShares = form.shares.map { it.personId }.toSet()
        return ExpenseEditUiState(
            form = form,
            isNew = form.original == null,
            conversions = snapshot?.let { s ->
                CurrencyCode.entries.filter { it != form.currency }.mapNotNull { s.convertOrNull(money, it) }
            }.orEmpty(),
            ratesDate = snapshot?.date,
            categories = activeCategories,
            quickCategories = quick,
            selectedCategory = selected,
            accounts = refs.accounts.filter { !it.archived || it.id == form.accountId },
            tagSuggestions = refs.tags
                .filter { t -> form.tags.none { it.equals(t, ignoreCase = true) } }
                .filter { draft.isEmpty() || it.lowercase().startsWith(draft) }
                .take(8),
            persons = refs.persons.filter { !it.archived || it.id in inShares },
            myShare = shareInputs?.takeIf { it.isNotEmpty() && shareError == null }?.fold(money) { acc, s -> acc - s.amount },
            shareError = shareError,
        )
    }

    fun onKey(key: KeypadKey) = form.update {
        it.copy(amount = it.amount.press(key, it.currency.fractionDigits), currencySwitchedFrom = null)
    }

    fun onCurrency(currency: CurrencyCode) = form.update {
        if (it.shares.any { s -> s.settled }) return@update it
        it.copy(amount = it.amount.fitTo(currency), currencySwitchedFrom = null).withCurrency(currency)
    }

    fun onCategory(id: Long) = form.update { it.copy(categoryId = id) }

    /** Выбор счёта переключает валюту ввода на валюту счёта (её можно сменить вручную). */
    fun onAccount(method: Account) = form.update {
        // Погашенная доля держит валюту — меняем только счёт.
        if (it.shares.any { s -> s.settled }) return@update it.copy(accountId = method.id)
        val currency = method.currency
        it.copy(
            accountId = method.id,
            amount = it.amount.fitTo(currency),
            currencySwitchedFrom = it.amount.currencySwitchedFrom(it.currency, currency, it.currencySwitchedFrom),
        ).withCurrency(currency)
    }

    /** Выбор человека в разделении; погашенную долю убрать нельзя. */
    fun onSplitToggle(personId: Long) = form.update { f ->
        val existing = f.shares.firstOrNull { it.personId == personId }
        when {
            existing == null -> f.copy(shares = f.shares + ShareDraft(personId, ""), sharesChanged = true)
            existing.settled -> f
            else -> f.copy(shares = f.shares - existing, sharesChanged = true)
        }
    }

    fun onShareAmount(personId: Long, text: String) = form.update { f ->
        f.copy(shares = f.shares.map { if (it.personId == personId && !it.settled) it.copy(amountText = text) else it }, sharesChanged = true)
    }

    /** «Поровну» на меня и выбранных; погашенные доли не трогаем, остаток от деления — мне. */
    fun onSplitEqually() = form.update { f ->
        if (f.shares.none { !it.settled }) return@update f
        f.copy(shares = f.shares.splitEqually(f.amount.toMoney(f.currency)), sharesChanged = true)
    }

    /** Новый человек сразу попадает в разделение. */
    fun addPerson(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { onSplitToggle(savePerson(0, name)) }
    }

    fun onDate(date: LocalDate) = form.update { it.copy(dateTime = LocalDateTime.of(date, it.dateTime.toLocalTime())) }

    fun onTime(time: LocalTime) = form.update { it.copy(dateTime = LocalDateTime.of(it.dateTime.toLocalDate(), time)) }

    fun onComment(text: String) = form.update { it.copy(comment = text) }

    /** Запятая или Enter завершают тег — так можно набрать несколько тегов подряд. */
    fun onTagDraft(text: String) {
        if (text.endsWith(',') || text.endsWith('\n')) {
            addTag(text.dropLast(1))
        } else {
            form.update { it.copy(tagDraft = text) }
        }
    }

    fun addTag(tag: String = form.value.tagDraft) = form.update {
        val clean = tag.trim()
        if (clean.isEmpty() || it.tags.any { t -> t.equals(clean, ignoreCase = true) }) {
            it.copy(tagDraft = "")
        } else {
            it.copy(tags = it.tags + clean, tagDraft = "")
        }
    }

    fun removeTag(tag: String) = form.update { it.copy(tags = it.tags - tag) }

    fun save() {
        val current = state.value ?: return
        if (!current.canSave) return
        val f = form.value
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            // Время не трогали — сохраняем исходный Instant с секундами, чтобы не «сдвинуть» расход.
            val original = f.original
            val occurredAt = if (original != null &&
                f.dateTime == LocalDateTime.ofInstant(original.occurredAt, clock.zone)
            ) {
                original.occurredAt
            } else {
                f.dateTime.atZone(clock.zone).toInstant()
            }
            val input = ExpenseInput(
                // original — и при открытии по id, и для уже подтверждённой покупки.
                id = original?.id ?: 0L,
                amount = f.amount.toMoney(f.currency),
                categoryId = f.categoryId!!,
                accountId = f.accountId!!,
                occurredAt = occurredAt,
                comment = f.comment,
                // Недописанный тег в поле тоже сохраняем — иначе он молча потеряется.
                tags = f.tags + listOfNotNull(f.tagDraft.trim().takeIf { it.isNotEmpty() }),
                shares = if (f.sharesChanged) f.shares.toInputs(f.currency) else null,
            )
            // Покупка — через ConfirmCapture: под его замком, чтобы не задвоить с кнопкой «Добавить».
            val savedId = if (captureId != 0L) confirmCapture.saveEdited(captureId, input) else saveExpense(input)
            // Новый расход из чека: его категория запоминается как категория сети.
            f.pendingReceiptId?.let { attachReceipt(it, savedId, rememberCategoryId = if (original == null) input.categoryId else null) }
            _events.send(ExpenseEditEvent.Close)
        }
    }

    private data class References(
        val categories: List<Category>,
        val accounts: List<Account>,
        val recentCategoryIds: List<Long>,
        val tags: List<String>,
        val persons: List<Person>,
    )

    private companion object {
        const val QUICK_CATEGORIES = 6
    }
}

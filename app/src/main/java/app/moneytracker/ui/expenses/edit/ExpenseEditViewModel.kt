package app.moneytracker.ui.expenses.edit

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
    /** Исходный расход при редактировании. */
    val original: Expense? = null,
    val saving: Boolean = false,
)

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
) {
    val canSave: Boolean get() = form.loaded && !form.saving && !form.amount.isEmpty &&
        form.amount.toMoney(form.currency).minor > 0 && form.categoryId != null && form.accountId != null
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
) : ViewModel() {

    private val expenseId = savedStateHandle.toRoute<ExpenseEditRoute>().id
    private val form = MutableStateFlow(ExpenseForm(dateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)))

    private val _events = Channel<ExpenseEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // С архивными: у старого расхода категория могла уйти в архив, но в форме она должна отображаться.
    private val references = combine(
        categoryRepository.observeAll(includeArchived = true),
        accountRepository.observeAll(includeArchived = true),
        expenses.observeRecentCategoryIds(QUICK_CATEGORIES),
        expenses.observeTags(),
    ) { categories, methods, recent, tags -> References(categories, methods, recent, tags) }

    val state: StateFlow<ExpenseEditUiState?> =
        combine(form, references, ratesRepository.observeCurrent()) { form, refs, current ->
            buildState(form, refs, current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (expenseId != 0L) {
            val e = expenses.get(expenseId)
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
            )
        } else {
            val s = settings.settings.first()
            val methods = accountRepository.getAll(includeArchived = false)
            val method = methods.firstOrNull { it.id == s.defaultAccountId } ?: methods.firstOrNull()
            form.update {
                it.copy(loaded = true, accountId = method?.id, currency = method?.currency ?: s.inputCurrency)
            }
        }
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
        return ExpenseEditUiState(
            form = form,
            isNew = form.original == null,
            conversions = snapshot?.let { s ->
                CurrencyCode.entries.filter { it != form.currency }.map { s.convert(money, it) }
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
        )
    }

    fun onKey(key: KeypadKey) = form.update { it.copy(amount = it.amount.press(key, it.currency.fractionDigits)) }

    fun onCurrency(currency: CurrencyCode) = form.update { it.copy(currency = currency, amount = it.amount.fitTo(currency)) }

    fun onCategory(id: Long) = form.update { it.copy(categoryId = id) }

    /** Выбор счёта переключает валюту ввода на валюту счёта (её можно сменить вручную). */
    fun onAccount(method: Account) = form.update {
        val currency = method.currency
        it.copy(accountId = method.id, currency = currency, amount = it.amount.fitTo(currency))
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
            saveExpense(
                ExpenseInput(
                    id = expenseId,
                    amount = f.amount.toMoney(f.currency),
                    categoryId = f.categoryId!!,
                    accountId = f.accountId!!,
                    occurredAt = occurredAt,
                    comment = f.comment,
                    // Недописанный тег в поле тоже сохраняем — иначе он молча потеряется.
                    tags = f.tags + listOfNotNull(f.tagDraft.trim().takeIf { it.isNotEmpty() }),
                ),
            )
            _events.send(ExpenseEditEvent.Close)
        }
    }

    private data class References(
        val categories: List<Category>,
        val accounts: List<Account>,
        val recentCategoryIds: List<Long>,
        val tags: List<String>,
    )

    private companion object {
        const val QUICK_CATEGORIES = 6
    }
}

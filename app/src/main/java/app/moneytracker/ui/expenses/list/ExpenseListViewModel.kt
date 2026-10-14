package app.moneytracker.ui.expenses.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DaySection
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.model.ExpenseSections
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.Total
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.CheckBudgetsUseCase
import app.moneytracker.data.db.DefaultNames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class ExpenseListUiState(
    val loading: Boolean = true,
    val sections: List<DaySection> = emptyList(),
    val displayCurrency: CurrencyCode = CurrencyCode.RSD,
    val month: YearMonth = YearMonth.now(),
    val monthTotal: Total? = null,
    /** Итог по найденному — только когда фильтр или поиск активны. */
    val filteredTotal: Total? = null,
    val filteredCount: Int = 0,
    val filter: ExpenseFilter = ExpenseFilter(),
    val categories: List<Category> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Заголовок для списка, открытого из аналитики (название категории или способа оплаты). */
    val title: String? = null,
)

sealed interface ExpenseListEvent {
    data class Deleted(val expense: Expense) : ExpenseListEvent
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ExpenseListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val expenses: ExpenseRepository,
    categoryRepository: CategoryRepository,
    accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    clock: Clock,
    private val defaultNames: DefaultNames,
    private val checkBudgets: CheckBudgetsUseCase,
) : ViewModel() {

    private val month = YearMonth.now(clock)

    /**
     * Аргументы [FilteredExpensesRoute] лежат в SavedStateHandle под именами полей.
     * На обычной вкладке «Расходы» их нет — фильтр пустой.
     */
    private val initialFilter: ExpenseFilter = run {
        val from = savedStateHandle.get<Long>("fromEpochDay") ?: return@run ExpenseFilter()
        val to = savedStateHandle.get<Long>("toEpochDay") ?: return@run ExpenseFilter()
        val categoryId = savedStateHandle.get<Long>("categoryId") ?: 0
        val methodId = savedStateHandle.get<Long>("accountId") ?: 0
        ExpenseFilter(
            categoryIds = if (categoryId != 0L) setOf(categoryId) else emptySet(),
            accountIds = if (methodId != 0L) setOf(methodId) else emptySet(),
            from = LocalDate.ofEpochDay(from),
            to = LocalDate.ofEpochDay(to),
        )
    }

    /** Фильтр без текста поиска: поиск хранится отдельно, чтобы его можно было дебаунсить. */
    private val filter = MutableStateFlow(initialFilter)

    /** Текст поиска для поля ввода — обновляется мгновенно, в запрос идёт с задержкой. */
    val query = MutableStateFlow("")

    private val _events = Channel<ExpenseListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Архивные категории/способы тоже нужны: старые расходы могут ссылаться на них.
    private val categories = categoryRepository.observeAll(includeArchived = true)
    private val accounts = accountRepository.observeAll(includeArchived = true)

    private val effectiveFilter = combine(
        filter,
        query.debounce(250).distinctUntilChanged(),
        categories,
    ) { f, q, cats -> f.copy(query = q).withSubcategories(cats) }.distinctUntilChanged()

    private val filtered = effectiveFilter.flatMapLatest { expenses.observe(it) }

    private val monthExpenses = expenses.observeBetween(month.atDay(1), month.atEndOfMonth())

    private val references = combine(categories, accounts, expenses.observeTags()) { c, p, t -> Triple(c, p, t) }

    val state: StateFlow<ExpenseListUiState> = combine(
        filtered,
        monthExpenses,
        references,
        settingsRepository.settings,
        combine(filter, effectiveFilter) { raw, effective -> raw to effective },
    ) { list, monthList, (cats, methods, tags), settings: AppSettings, (rawFilter, effective) ->
        val currency = settings.displayCurrency
        val active = !effective.isEmpty
        ExpenseListUiState(
            loading = false,
            sections = ExpenseSections.byDay(list, cats.associateBy { it.id }, methods.associateBy { it.id }, currency),
            displayCurrency = currency,
            month = month,
            monthTotal = Total.of(monthList, currency),
            filteredTotal = if (active) Total.of(list, currency) else null,
            filteredCount = list.size,
            filter = rawFilter,
            categories = cats.filter { !it.archived },
            accounts = methods.filter { !it.archived },
            tags = tags,
            title = titleFor(cats, methods),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpenseListUiState())

    private fun titleFor(cats: List<Category>, methods: List<Account>): String? {
        val categoryId = initialFilter.categoryIds.singleOrNull()
        val methodId = initialFilter.accountIds.singleOrNull()
        return when {
            categoryId != null -> cats.firstOrNull { it.id == categoryId }?.let { defaultNames.resolve(it.defaultKey, it.name) }
            methodId != null -> methods.firstOrNull { it.id == methodId }?.let { defaultNames.resolve(it.defaultKey, it.name) }
            else -> null
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setFilter(newFilter: ExpenseFilter) {
        filter.value = newFilter
    }

    fun clearFilter() {
        filter.value = ExpenseFilter()
        query.value = ""
    }

    fun setDisplayCurrency(currency: CurrencyCode) {
        viewModelScope.launch { settingsRepository.setDisplayCurrency(currency) }
    }

    /** Удаляем сразу, а копию отдаём в Snackbar: «Отменить» вставит её обратно с тем же id. */
    fun delete(id: Long) {
        viewModelScope.launch {
            val expense = expenses.get(id) ?: return@launch
            expenses.delete(id)
            _events.send(ExpenseListEvent.Deleted(expense))
        }
    }

    fun restore(expense: Expense) {
        viewModelScope.launch {
            expenses.insert(expense)
            checkBudgets()
        }
    }
}

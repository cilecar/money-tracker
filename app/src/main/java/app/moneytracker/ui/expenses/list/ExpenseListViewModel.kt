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
import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.MonthSummary
import app.moneytracker.domain.model.OperationKind
import app.moneytracker.domain.model.Operations
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import app.moneytracker.domain.repository.TransferRepository
import app.moneytracker.domain.model.Transfer
import app.moneytracker.domain.usecase.DeleteTransferUseCase
import app.moneytracker.domain.usecase.DeleteRefundUseCase
import app.moneytracker.domain.usecase.ObserveMonthSummaryUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /** «Осталось до конца месяца» — доходы, траты и остаток текущего месяца. */
    val monthSummary: MonthSummary? = null,
    /** Итог трат по найденному (за вычетом возвратов) — только когда фильтр или поиск активны. */
    val filteredTotal: Total? = null,
    val filteredIncome: Total? = null,
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
    data class IncomeDeleted(val income: Income) : ExpenseListEvent
    data class RefundDeleted(val refund: Refund) : ExpenseListEvent
    data class TransferDeleted(val transfer: Transfer, val fee: Expense?) : ExpenseListEvent
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
    private val incomes: IncomeRepository,
    private val refunds: RefundRepository,
    incomeCategoryRepository: IncomeCategoryRepository,
    private val deleteRefund: DeleteRefundUseCase,
    observeMonthSummary: ObserveMonthSummaryUseCase,
    private val transfers: TransferRepository,
    private val deleteTransfer: DeleteTransferUseCase,
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

    /** Расходы — SQL-фильтром, доходы и возвраты — тем же фильтром в Kotlin (их немного). */
    private val filtered = effectiveFilter.flatMapLatest { f ->
        combine(
            if (f.includes(OperationKind.EXPENSE)) expenses.observe(f) else flowOf(emptyList()),
            incomes.observeBetween(f.from, f.to).map { list -> list.filter(f::matches) },
            refunds.observeBetween(f.from, f.to).map { list -> list.filter(f::matches) },
            transfers.observeBetween(f.from, f.to).map { list -> list.filter(f::matches) },
        ) { e, i, r, t -> Operations(e, i, r, t) }
    }

    private val monthSummary = settingsRepository.settings.map { it.displayCurrency }.distinctUntilChanged()
        .flatMapLatest { observeMonthSummary(it) }

    private val references = combine(
        categories,
        accounts,
        expenses.observeTags(),
        incomeCategoryRepository.observeAll(includeArchived = true),
    ) { c, p, t, ic -> References(c, p, t, ic) }

    val state: StateFlow<ExpenseListUiState> = combine(
        filtered,
        monthSummary,
        references,
        settingsRepository.settings,
        combine(filter, effectiveFilter) { raw, effective -> raw to effective },
    ) { ops, summary, refs, settings: AppSettings, (rawFilter, effective) ->
        val (cats, methods, tags, incomeCats) = refs
        val currency = settings.displayCurrency
        val active = !effective.isEmpty
        ExpenseListUiState(
            loading = false,
            sections = ExpenseSections.byDay(ops, cats.associateBy { it.id }, methods.associateBy { it.id }, incomeCats.associateBy { it.id }, currency),
            displayCurrency = currency,
            month = month,
            // Сводка — в валюте отображения; пока она не пересчиталась после смены валюты, не показываем старую.
            monthSummary = summary.takeIf { it.remaining.currency == currency },
            filteredTotal = if (active) Total.spending(ops.expenses, ops.refunds, currency) else null,
            filteredIncome = if (active) Total.of(ops.incomes, currency) else null,
            filteredCount = ops.size,
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

    fun deleteIncome(id: Long) {
        viewModelScope.launch {
            val income = incomes.get(id) ?: return@launch
            incomes.delete(id)
            _events.send(ExpenseListEvent.IncomeDeleted(income))
        }
    }

    fun restore(income: Income) {
        viewModelScope.launch { incomes.insert(income) }
    }

    fun deleteRefund(id: Long) {
        viewModelScope.launch {
            val refund = deleteRefund.invoke(id) ?: return@launch
            _events.send(ExpenseListEvent.RefundDeleted(refund))
        }
    }

    fun restore(refund: Refund) {
        viewModelScope.launch {
            // Исходный расход могли удалить, пока висел Snackbar, — тогда возврат без привязки.
            val expenseId = refund.expenseId?.takeIf { expenses.get(it) != null }
            refunds.insert(refund.copy(expenseId = expenseId))
            checkBudgets()
        }
    }

    fun deleteTransfer(id: Long) {
        viewModelScope.launch {
            val (transfer, fee) = deleteTransfer.invoke(id) ?: return@launch
            _events.send(ExpenseListEvent.TransferDeleted(transfer, fee))
        }
    }

    fun restore(transfer: Transfer, fee: Expense?) {
        viewModelScope.launch {
            transfers.save(transfer, fee)
            if (fee != null) checkBudgets()
        }
    }

    private data class References(
        val categories: List<Category>,
        val accounts: List<Account>,
        val tags: List<String>,
        val incomeCategories: List<IncomeCategory>,
    )
}

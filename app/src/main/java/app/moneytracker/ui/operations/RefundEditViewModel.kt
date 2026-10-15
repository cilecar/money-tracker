package app.moneytracker.ui.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.RefundRepository
import app.moneytracker.domain.usecase.RefundInput
import app.moneytracker.domain.usecase.SaveRefundUseCase
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.expenses.edit.KeypadKey
import app.moneytracker.ui.navigation.RefundEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

data class RefundForm(
    val loaded: Boolean = false,
    val amount: AmountInput = AmountInput(),
    val currency: CurrencyCode = CurrencyCode.RSD,
    /** Исходный расход; null — возврат без привязки, категорию выбирают вручную. */
    val expense: Expense? = null,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val dateTime: LocalDateTime,
    val comment: String = "",
    val original: Refund? = null,
    val saving: Boolean = false,
)

data class RefundEditUiState(
    val form: RefundForm,
    val conversions: List<Money>,
    val ratesDate: LocalDate?,
    val categories: List<Category>,
    val selectedCategory: Category?,
    val accounts: List<Account>,
) {
    val isNew: Boolean get() = form.original == null
    /** Категорию берём из расхода — менять её можно только у возврата без расхода. */
    val categoryEditable: Boolean get() = form.expense == null && form.original?.expenseId == null
    val canSave: Boolean get() = form.loaded && !form.saving && form.amount.toMoney(form.currency).minor > 0 &&
        form.categoryId != null && form.accountId != null
}

@HiltViewModel
class RefundEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val refunds: RefundRepository,
    private val expenses: ExpenseRepository,
    categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    ratesRepository: ExchangeRateRepository,
    private val saveRefund: SaveRefundUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<RefundEditRoute>()
    private val form = MutableStateFlow(RefundForm(dateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)))

    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close = _close.receiveAsFlow()

    val state: StateFlow<RefundEditUiState?> = combine(
        form,
        categoryRepository.observeAll(includeArchived = true),
        accountRepository.observeAll(includeArchived = true),
        ratesRepository.observeCurrent(),
    ) { f, cats, accounts, current -> buildState(f, cats, accounts, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        when {
            route.id != 0L -> {
                val r = refunds.get(route.id) ?: return _close.send(Unit)
                form.value = RefundForm(
                    loaded = true,
                    amount = AmountInput.from(r.amount),
                    currency = r.amount.currency,
                    expense = r.expenseId?.let { expenses.get(it) },
                    categoryId = r.categoryId,
                    accountId = r.accountId,
                    dateTime = LocalDateTime.ofInstant(r.occurredAt, clock.zone),
                    comment = r.comment.orEmpty(),
                    original = r,
                )
            }
            route.expenseId != 0L -> {
                // По расходу: та же сумма, валюта и счёт — обычно возвращают всё на ту же карту.
                val e = expenses.get(route.expenseId) ?: return _close.send(Unit)
                form.update {
                    it.copy(loaded = true, amount = AmountInput.from(e.amount), currency = e.amount.currency, expense = e, categoryId = e.categoryId, accountId = e.accountId)
                }
            }
            else -> {
                val account = accountRepository.getAll(includeArchived = false).firstOrNull()
                form.update { it.copy(loaded = true, accountId = account?.id, currency = account?.currency ?: it.currency) }
            }
        }
    }

    private fun buildState(f: RefundForm, cats: List<Category>, accounts: List<Account>, current: CurrentRates?): RefundEditUiState {
        val snapshot = if (f.original != null) f.original.rates else current?.snapshot
        val money = f.amount.toMoney(f.currency)
        return RefundEditUiState(
            form = f,
            conversions = snapshot?.let { s -> CurrencyCode.entries.filter { it != f.currency }.mapNotNull { s.convertOrNull(money, it) } }.orEmpty(),
            ratesDate = snapshot?.date,
            categories = cats.filter { !it.archived },
            selectedCategory = cats.firstOrNull { it.id == f.categoryId },
            accounts = accounts.filter { !it.archived || it.id == f.accountId },
        )
    }

    fun onKey(key: KeypadKey) = form.update { it.copy(amount = it.amount.press(key, it.currency.fractionDigits)) }

    fun onCurrency(currency: CurrencyCode) = form.update { it.copy(currency = currency, amount = it.amount.fitTo(currency)) }

    fun onCategory(id: Long) = form.update { it.copy(categoryId = id) }

    fun onAccount(account: Account) = form.update { it.copy(accountId = account.id) }

    fun onDate(date: LocalDate) = form.update { it.copy(dateTime = LocalDateTime.of(date, it.dateTime.toLocalTime())) }

    fun onTime(time: LocalTime) = form.update { it.copy(dateTime = LocalDateTime.of(it.dateTime.toLocalDate(), time)) }

    fun onComment(text: String) = form.update { it.copy(comment = text) }

    fun save() {
        val s = state.value ?: return
        if (!s.canSave) return
        val f = s.form
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            saveRefund(
                RefundInput(
                    id = route.id,
                    amount = f.amount.toMoney(f.currency),
                    accountId = f.accountId!!,
                    expenseId = f.expense?.id,
                    categoryId = f.categoryId,
                    occurredAt = occurredAt(f.dateTime, f.original?.occurredAt, clock),
                    comment = f.comment,
                ),
            )
            _close.send(Unit)
        }
    }
}

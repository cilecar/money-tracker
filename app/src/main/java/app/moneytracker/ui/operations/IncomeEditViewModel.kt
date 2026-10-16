package app.moneytracker.ui.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.IncomeInput
import app.moneytracker.domain.usecase.SaveIncomeUseCase
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.expenses.edit.KeypadKey
import app.moneytracker.ui.expenses.edit.currencySwitchedFrom
import app.moneytracker.ui.navigation.IncomeEditRoute
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

data class IncomeForm(
    val loaded: Boolean = false,
    val amount: AmountInput = AmountInput(),
    val currency: CurrencyCode = CurrencyCode.RSD,
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val dateTime: LocalDateTime,
    val comment: String = "",
    /** Валюта до того, как её сменил выбор счёта, — пока пользователь не тронул сумму или валюту. */
    val currencySwitchedFrom: CurrencyCode? = null,
    val original: Income? = null,
    val saving: Boolean = false,
)

data class IncomeEditUiState(
    val form: IncomeForm,
    val conversions: List<Money>,
    val ratesDate: LocalDate?,
    val categories: List<IncomeCategory>,
    val accounts: List<Account>,
) {
    val isNew: Boolean get() = form.original == null
    val canSave: Boolean get() = form.loaded && !form.saving && form.amount.toMoney(form.currency).minor > 0 &&
        form.categoryId != null && form.accountId != null
}

@HiltViewModel
class IncomeEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val incomes: IncomeRepository,
    categoryRepository: IncomeCategoryRepository,
    private val accountRepository: AccountRepository,
    ratesRepository: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val saveIncome: SaveIncomeUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val incomeId = savedStateHandle.toRoute<IncomeEditRoute>().id
    private val form = MutableStateFlow(IncomeForm(dateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)))

    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close = _close.receiveAsFlow()

    val state: StateFlow<IncomeEditUiState?> = combine(
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
        if (incomeId != 0L) {
            val i = incomes.get(incomeId) ?: return _close.send(Unit)
            form.value = IncomeForm(
                loaded = true,
                amount = AmountInput.from(i.amount),
                currency = i.amount.currency,
                categoryId = i.categoryId,
                accountId = i.accountId,
                dateTime = LocalDateTime.ofInstant(i.occurredAt, clock.zone),
                comment = i.comment.orEmpty(),
                original = i,
            )
        } else {
            val s = settings.settings.first()
            val accounts = accountRepository.getAll(includeArchived = false)
            val account = accounts.firstOrNull { it.id == s.defaultAccountId } ?: accounts.firstOrNull()
            form.update { it.copy(loaded = true, accountId = account?.id, currency = account?.currency ?: s.inputCurrency) }
        }
    }

    private fun buildState(f: IncomeForm, cats: List<IncomeCategory>, accounts: List<Account>, current: CurrentRates?): IncomeEditUiState {
        // Как у расхода: при правке пересчёт по снапшоту самого дохода.
        val snapshot = if (f.original != null) f.original.rates else current?.snapshot
        val money = f.amount.toMoney(f.currency)
        val active = cats.filter { !it.archived || it.id == f.categoryId }
        return IncomeEditUiState(
            // Новый доход — по умолчанию первая категория («Зарплата»).
            form = if (f.loaded && f.categoryId == null && f.original == null) f.copy(categoryId = active.firstOrNull()?.id) else f,
            conversions = snapshot?.let { s -> CurrencyCode.entries.filter { it != f.currency }.mapNotNull { s.convertOrNull(money, it) } }.orEmpty(),
            ratesDate = snapshot?.date,
            categories = active,
            accounts = accounts.filter { !it.archived || it.id == f.accountId },
        )
    }

    fun onKey(key: KeypadKey) = form.update {
        it.copy(amount = it.amount.press(key, it.currency.fractionDigits), currencySwitchedFrom = null)
    }

    fun onCurrency(currency: CurrencyCode) = form.update {
        it.copy(currency = currency, amount = it.amount.fitTo(currency), currencySwitchedFrom = null)
    }

    fun onCategory(id: Long) = form.update { it.copy(categoryId = id) }

    fun onAccount(account: Account) = form.update {
        it.copy(
            accountId = account.id,
            currency = account.currency,
            amount = it.amount.fitTo(account.currency),
            currencySwitchedFrom = it.amount.currencySwitchedFrom(it.currency, account.currency, it.currencySwitchedFrom),
        )
    }

    fun onDate(date: LocalDate) = form.update { it.copy(dateTime = LocalDateTime.of(date, it.dateTime.toLocalTime())) }

    fun onTime(time: LocalTime) = form.update { it.copy(dateTime = LocalDateTime.of(it.dateTime.toLocalDate(), time)) }

    fun onComment(text: String) = form.update { it.copy(comment = text) }

    fun save() {
        val s = state.value ?: return
        if (!s.canSave) return
        val f = s.form
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            saveIncome(
                IncomeInput(
                    id = incomeId,
                    amount = f.amount.toMoney(f.currency),
                    accountId = f.accountId!!,
                    categoryId = f.categoryId!!,
                    occurredAt = occurredAt(f.dateTime, f.original?.occurredAt, clock),
                    comment = f.comment,
                ),
            )
            _close.send(Unit)
        }
    }
}

/** Время не трогали — сохраняем исходный Instant с секундами, чтобы не сдвинуть операцию. */
internal fun occurredAt(dateTime: LocalDateTime, original: java.time.Instant?, clock: Clock): java.time.Instant =
    if (original != null && dateTime == LocalDateTime.ofInstant(original, clock.zone)) original else dateTime.atZone(clock.zone).toInstant()

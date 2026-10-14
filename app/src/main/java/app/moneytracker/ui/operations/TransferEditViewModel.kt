package app.moneytracker.ui.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Transfer
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.TransferRepository
import app.moneytracker.domain.usecase.SaveTransferUseCase
import app.moneytracker.domain.usecase.TransferError
import app.moneytracker.domain.usecase.TransferInput
import app.moneytracker.domain.usecase.TransferResult
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.expenses.edit.KeypadKey
import app.moneytracker.ui.navigation.TransferEditRoute
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
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/** Какую сумму сейчас набирает цифровая клавиатура. */
enum class TransferField { FROM, TO, FEE }

data class TransferForm(
    val loaded: Boolean = false,
    val fromAccountId: Long? = null,
    val toAccountId: Long? = null,
    val fromAmount: AmountInput = AmountInput(),
    val toAmount: AmountInput = AmountInput(),
    /** Сумму зачисления ввели вручную — больше не копируем в неё сумму списания. */
    val toEdited: Boolean = false,
    val fee: AmountInput = AmountInput(),
    val active: TransferField = TransferField.FROM,
    val dateTime: LocalDateTime,
    val comment: String = "",
    val original: Transfer? = null,
    val saving: Boolean = false,
    val error: TransferError? = null,
)

data class TransferEditUiState(
    val form: TransferForm,
    val accounts: List<Account>,
    val from: Account?,
    val to: Account?,
    /** Фактический курс по введённым суммам (только при обмене валют). */
    val actualRate: BigDecimal?,
    /** Курс API: снапшот перевода при правке, текущий — для нового. */
    val apiRate: BigDecimal?,
    /** Подсказка: сколько вышло бы по курсу API (сумму зачисления мы за пользователя не подставляем). */
    val apiEstimate: Money?,
) {
    val isNew: Boolean get() = form.original == null
    val isExchange: Boolean get() = from != null && to != null && from.currency != to.currency
    val deviation: BigDecimal? get() = Transfer.deviationPercent(actualRate, apiRate)
    val canSave: Boolean get() = form.loaded && !form.saving && from != null && to != null && from.id != to.id &&
        form.fromAmount.toMoney(from.currency).minor > 0 && form.toAmount.toMoney(to.currency).minor > 0
}

@HiltViewModel
class TransferEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transfers: TransferRepository,
    private val expenses: ExpenseRepository,
    private val accountRepository: AccountRepository,
    ratesRepository: ExchangeRateRepository,
    private val saveTransfer: SaveTransferUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val transferId = savedStateHandle.toRoute<TransferEditRoute>().id
    private val form = MutableStateFlow(TransferForm(dateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)))

    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close = _close.receiveAsFlow()

    val state: StateFlow<TransferEditUiState?> = combine(
        form,
        accountRepository.observeAll(includeArchived = true),
        ratesRepository.observeCurrent(),
    ) { f, accounts, current -> buildState(f, accounts, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (transferId != 0L) {
            val t = transfers.get(transferId) ?: return _close.send(Unit)
            val fee = t.feeExpenseId?.let { expenses.get(it) }
            form.value = TransferForm(
                loaded = true,
                fromAccountId = t.fromAccountId,
                toAccountId = t.toAccountId,
                fromAmount = AmountInput.from(t.fromAmount),
                toAmount = AmountInput.from(t.toAmount),
                toEdited = true,
                fee = fee?.let { AmountInput.from(it.amount) } ?: AmountInput(),
                dateTime = LocalDateTime.ofInstant(t.occurredAt, clock.zone),
                comment = t.comment.orEmpty(),
                original = t,
            )
        } else {
            val accounts = accountRepository.getAll(includeArchived = false)
            form.update { it.copy(loaded = true, fromAccountId = accounts.getOrNull(0)?.id, toAccountId = accounts.getOrNull(1)?.id) }
        }
    }

    private fun buildState(f: TransferForm, accounts: List<Account>, current: CurrentRates?): TransferEditUiState {
        val from = accounts.firstOrNull { it.id == f.fromAccountId }
        val to = accounts.firstOrNull { it.id == f.toAccountId }
        val exchange = from != null && to != null && from.currency != to.currency
        val fromMoney = from?.let { f.fromAmount.toMoney(it.currency) }
        val toMoney = to?.let { f.toAmount.toMoney(it.currency) }
        val snapshot = if (f.original != null) f.original.apiRates else current?.snapshot
        return TransferEditUiState(
            form = f,
            accounts = accounts.filter { !it.archived || it.id == f.fromAccountId || it.id == f.toAccountId },
            from = from,
            to = to,
            actualRate = if (exchange && fromMoney != null && toMoney != null && fromMoney.minor > 0 && toMoney.minor > 0) Transfer.rateOf(fromMoney, toMoney) else null,
            apiRate = if (exchange) snapshot?.rate(from.currency, to.currency) else null,
            apiEstimate = if (exchange && fromMoney != null && fromMoney.minor > 0) snapshot?.convert(fromMoney, to.currency) else null,
        )
    }

    fun onField(field: TransferField) = form.update { it.copy(active = field) }

    fun onKey(key: KeypadKey) {
        val s = state.value ?: return
        form.update { f ->
            val err = f.copy(error = null)
            when (f.active) {
                TransferField.FROM -> {
                    val digits = s.from?.currency?.fractionDigits ?: 2
                    val amount = f.fromAmount.press(key, digits)
                    // Одна валюта: зачисление = списание, пока его не правили вручную.
                    if (!s.isExchange && !f.toEdited) err.copy(fromAmount = amount, toAmount = amount) else err.copy(fromAmount = amount)
                }
                TransferField.TO -> err.copy(toAmount = f.toAmount.press(key, s.to?.currency?.fractionDigits ?: 2), toEdited = true)
                TransferField.FEE -> err.copy(fee = f.fee.press(key, s.from?.currency?.fractionDigits ?: 2))
            }
        }
    }

    fun onFrom(account: Account) = form.update { f ->
        val to = if (account.id == f.toAccountId) f.fromAccountId else f.toAccountId
        f.copy(fromAccountId = account.id, toAccountId = to, fromAmount = f.fromAmount.fitTo(account.currency), fee = f.fee.fitTo(account.currency), error = null)
    }

    fun onTo(account: Account) = form.update { f ->
        val from = if (account.id == f.fromAccountId) f.toAccountId else f.fromAccountId
        f.copy(toAccountId = account.id, fromAccountId = from, toAmount = f.toAmount.fitTo(account.currency), error = null)
    }

    fun onDate(date: LocalDate) = form.update { it.copy(dateTime = LocalDateTime.of(date, it.dateTime.toLocalTime())) }

    fun onTime(time: LocalTime) = form.update { it.copy(dateTime = LocalDateTime.of(it.dateTime.toLocalDate(), time)) }

    fun onComment(text: String) = form.update { it.copy(comment = text) }

    fun save() {
        val s = state.value ?: return
        if (!s.canSave) return
        val f = s.form
        val from = s.from!!
        val to = s.to!!
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = saveTransfer(
                TransferInput(
                    id = transferId,
                    fromAccountId = from.id,
                    toAccountId = to.id,
                    fromAmount = f.fromAmount.toMoney(from.currency),
                    toAmount = f.toAmount.toMoney(to.currency),
                    fee = f.fee.toMoney(from.currency).takeIf { it.minor > 0 },
                    occurredAt = occurredAt(f.dateTime, f.original?.occurredAt, clock),
                    comment = f.comment,
                ),
            )
            when (result) {
                is TransferResult.Success -> _close.send(Unit)
                is TransferResult.Error -> form.update { it.copy(saving = false, error = result.error) }
            }
        }
    }
}

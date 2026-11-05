package app.moneytracker.ui.receipts

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.R
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ReceiptRepository
import app.moneytracker.domain.usecase.AttachReceiptUseCase
import app.moneytracker.domain.usecase.FindReceiptCandidatesUseCase
import app.moneytracker.domain.usecase.IntakeReceiptUseCase
import app.moneytracker.domain.usecase.IntakeResult
import app.moneytracker.domain.usecase.ReceiptCandidate
import app.moneytracker.domain.usecase.ReceiptCandidates
import app.moneytracker.ui.navigation.ExpenseEditRoute
import app.moneytracker.ui.navigation.ReceiptIntakeRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface IntakeUiState {
    /** Разбор ссылки и загрузка чека (до 5 с). */
    data object Working : IntakeUiState

    /** Не фискальный чек или неподдерживаемый тип. */
    data class Message(@StringRes val text: Int) : IntakeUiState

    data class Choose(
        val receiptId: Long,
        val total: Money,
        val matching: List<ReceiptCandidate>,
        val sameDay: List<Expense>,
        val showSameDay: Boolean,
    ) : IntakeUiState
}

sealed interface IntakeEvent {
    data class OpenReceipt(val receiptId: Long, val duplicate: Boolean) : IntakeEvent

    /** Форма расхода вместо экрана приёма (приём убирается из back stack). */
    data class OpenEditor(val route: ExpenseEditRoute) : IntakeEvent

    /** Чек прикреплён — назад. */
    data object Attached : IntakeEvent
}

/** Что показать после поиска кандидатов: без совпадений сразу открывается новый расход из чека. */
internal fun decide(receiptId: Long, total: Money, c: ReceiptCandidates): Pair<IntakeUiState?, IntakeEvent?> =
    if (c.matching.isEmpty()) {
        null to IntakeEvent.OpenEditor(ExpenseEditRoute(receiptId = receiptId))
    } else {
        IntakeUiState.Choose(receiptId, total, c.matching, c.sameDay, showSameDay = false) to null
    }

@HiltViewModel
class ReceiptIntakeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val intake: IntakeReceiptUseCase,
    private val findCandidates: FindReceiptCandidatesUseCase,
    private val attach: AttachReceiptUseCase,
    private val receipts: ReceiptRepository,
    categories: CategoryRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ReceiptIntakeRoute>()

    private val _state = MutableStateFlow<IntakeUiState>(IntakeUiState.Working)
    val state: StateFlow<IntakeUiState> = _state.asStateFlow()

    private val _events = Channel<IntakeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val categories: StateFlow<Map<Long, Category>> = categories.observeAll(includeArchived = true)
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val receiptId = if (route.receiptId != 0L) route.receiptId else {
            when (val r = intake(route.text)) {
                IntakeResult.NotFiscal -> return show(R.string.receipt_not_fiscal)
                IntakeResult.Unsupported -> return show(R.string.receipt_unsupported)
                is IntakeResult.Duplicate -> return _events.send(IntakeEvent.OpenReceipt(r.receiptId, duplicate = true))
                is IntakeResult.Added -> r.receiptId
            }
        }
        val receipt = receipts.get(receiptId) ?: return show(R.string.receipt_not_fiscal)
        val (state, event) = decide(receiptId, Money(receipt.totalMinor, CurrencyCode.RSD), findCandidates(receiptId))
        state?.let { _state.value = it }
        event?.let { _events.send(it) }
    }

    private fun show(@StringRes text: Int) {
        _state.value = IntakeUiState.Message(text)
    }

    fun onAttach(expenseId: Long) {
        val s = _state.value as? IntakeUiState.Choose ?: return
        viewModelScope.launch {
            if (attach(s.receiptId, expenseId)) _events.send(IntakeEvent.Attached) else show(R.string.receipt_attached_elsewhere)
        }
    }

    fun onCreateNew() {
        val s = _state.value as? IntakeUiState.Choose ?: return
        viewModelScope.launch { _events.send(IntakeEvent.OpenEditor(ExpenseEditRoute(receiptId = s.receiptId))) }
    }

    fun onCapture(captureId: Long) {
        val s = _state.value as? IntakeUiState.Choose ?: return
        viewModelScope.launch { _events.send(IntakeEvent.OpenEditor(ExpenseEditRoute(captureId = captureId, receiptId = s.receiptId))) }
    }

    fun onShowSameDay() = _state.update { (it as? IntakeUiState.Choose)?.copy(showSameDay = true) ?: it }
}

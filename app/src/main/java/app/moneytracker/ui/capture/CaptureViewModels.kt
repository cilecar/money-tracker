package app.moneytracker.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.capture.CaptureNotifications
import app.moneytracker.capture.DebugNotification
import app.moneytracker.capture.NotificationDebugStore
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.CaptureRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.CaptureSuggestion
import app.moneytracker.domain.usecase.ConfirmCaptureUseCase
import app.moneytracker.domain.usecase.DismissCaptureUseCase
import app.moneytracker.domain.usecase.SuggestCaptureUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutoCaptureUiState(
    val enabled: Boolean = false,
    val debug: Boolean = false,
    val pending: Int = 0,
    val debugCount: Int = 0,
)

@HiltViewModel
class AutoCaptureViewModel @Inject constructor(
    private val settings: SettingsRepository,
    captures: CaptureRepository,
    debugStore: NotificationDebugStore,
) : ViewModel() {
    init {
        viewModelScope.launch { debugStore.load() }
    }

    val state: StateFlow<AutoCaptureUiState> = combine(
        settings.settings,
        captures.observePending(),
        debugStore.items,
    ) { s, pending, debug -> AutoCaptureUiState(s.autoCapture, s.captureDebug, pending.size, debug?.size ?: 0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoCaptureUiState())

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { settings.setAutoCapture(enabled) }

    fun setDebug(enabled: Boolean) = viewModelScope.launch { settings.setCaptureDebug(enabled) }
}

data class PendingItem(val purchase: CapturedPurchase, val suggestion: CaptureSuggestion, val category: Category?, val account: Account?)

@HiltViewModel
class PendingCapturesViewModel @Inject constructor(
    captures: CaptureRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    private val suggest: SuggestCaptureUseCase,
    private val confirm: ConfirmCaptureUseCase,
    private val dismiss: DismissCaptureUseCase,
    private val notifications: CaptureNotifications,
) : ViewModel() {

    // Подсказки пересчитываются при любом изменении: правило мерчанта или цифры карты у счёта могли появиться.
    val items: StateFlow<List<PendingItem>?> = combine(
        captures.observePending(),
        categories.observeAll(includeArchived = true),
        accounts.observeAll(includeArchived = true),
    ) { list, cats, accs -> Triple(list, cats, accs) }
        .map { (list, cats, accs) ->
            list.map { p ->
                val s = suggest(p)
                PendingItem(p, s, cats.firstOrNull { it.id == s.categoryId }, accs.firstOrNull { it.id == s.accountId })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun add(id: Long) = viewModelScope.launch {
        if (confirm(id) != null) notifications.cancel(id)
    }

    fun dismiss(id: Long) = viewModelScope.launch {
        dismiss.invoke(id)
        notifications.cancel(id)
    }
}

@HiltViewModel
class CaptureDebugViewModel @Inject constructor(private val store: NotificationDebugStore) : ViewModel() {
    init {
        viewModelScope.launch { store.load() }
    }

    val items: StateFlow<List<DebugNotification>?> = store.items

    fun clear() = viewModelScope.launch { store.clear() }
}

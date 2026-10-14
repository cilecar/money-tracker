package app.moneytracker.ui.paymentmethods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.PaymentMethodType
import app.moneytracker.domain.model.PaymentMethodUsage
import app.moneytracker.domain.repository.PaymentMethodRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.ArchivePaymentMethodUseCase
import app.moneytracker.domain.usecase.DeletePaymentMethodUseCase
import app.moneytracker.domain.usecase.PaymentMethodError
import app.moneytracker.domain.usecase.PaymentMethodResult
import app.moneytracker.domain.usecase.SavePaymentMethodUseCase
import app.moneytracker.data.db.DefaultNames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Состояние диалога редактирования (null — диалог закрыт). */
data class PaymentMethodDraft(
    val original: PaymentMethod? = null,
    val name: String = "",
    val type: PaymentMethodType = PaymentMethodType.CARD,
    val currency: CurrencyCode? = null,
    val error: PaymentMethodError? = null,
)

sealed interface PaymentDeleteDialog {
    data class Confirm(val method: PaymentMethod) : PaymentDeleteDialog
    data class Move(val method: PaymentMethod, val usage: PaymentMethodUsage, val targets: List<PaymentMethod>) : PaymentDeleteDialog
    data class Blocked(val method: PaymentMethod, val error: PaymentMethodError) : PaymentDeleteDialog
}

data class PaymentMethodsUiState(
    val active: List<PaymentMethod> = emptyList(),
    val archived: List<PaymentMethod> = emptyList(),
    val defaultId: Long? = null,
    val draft: PaymentMethodDraft? = null,
    val deleteDialog: PaymentDeleteDialog? = null,
)

@HiltViewModel
class PaymentMethodsViewModel @Inject constructor(
    private val repository: PaymentMethodRepository,
    settings: SettingsRepository,
    private val save: SavePaymentMethodUseCase,
    private val delete: DeletePaymentMethodUseCase,
    private val archive: ArchivePaymentMethodUseCase,
    private val defaultNames: DefaultNames,
) : ViewModel() {

    private val dialogs = MutableStateFlow(PaymentMethodsUiState())

    val state: StateFlow<PaymentMethodsUiState> = combine(
        repository.observeAll(includeArchived = true),
        settings.settings,
        dialogs,
    ) { all, s, d ->
        d.copy(
            active = all.filter { !it.archived },
            archived = all.filter { it.archived },
            // Если по умолчанию ничего не задано, фактически используется первый в списке.
            defaultId = s.defaultPaymentMethodId ?: all.firstOrNull { !it.archived }?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentMethodsUiState())

    fun openNew() = dialogs.update { it.copy(draft = PaymentMethodDraft()) }

    fun openEdit(method: PaymentMethod) = dialogs.update {
        it.copy(
            draft = PaymentMethodDraft(
                original = method,
                name = defaultNames.resolve(method.defaultKey, method.name),
                type = method.type,
                currency = method.currency,
            ),
        )
    }

    fun updateDraft(transform: (PaymentMethodDraft) -> PaymentMethodDraft) =
        dialogs.update { s -> s.copy(draft = s.draft?.let { transform(it).copy(error = null) }) }

    fun closeDraft() = dialogs.update { it.copy(draft = null) }

    fun saveDraft() {
        val d = dialogs.value.draft ?: return
        val original = d.original
        val keepKey = original?.defaultKey != null &&
            d.name.trim().equals(defaultNames.resolve(original.defaultKey, original.name), ignoreCase = true)
        val method = PaymentMethod(
            id = original?.id ?: 0,
            name = if (keepKey) original!!.name else d.name,
            type = d.type,
            currency = d.currency,
            defaultKey = if (keepKey) original!!.defaultKey else null,
            sortOrder = original?.sortOrder ?: 0,
            archived = original?.archived ?: false,
        )
        viewModelScope.launch {
            when (val r = save(method)) {
                is PaymentMethodResult.Success -> closeDraft()
                is PaymentMethodResult.Error -> dialogs.update { it.copy(draft = it.draft?.copy(error = r.error)) }
            }
        }
    }

    fun requestDelete(method: PaymentMethod) {
        viewModelScope.launch {
            val usage = delete.usage(method.id)
            val dialog = if (usage.needsTarget) {
                PaymentDeleteDialog.Move(method, usage, repository.getAll(includeArchived = false).filter { it.id != method.id })
            } else {
                PaymentDeleteDialog.Confirm(method)
            }
            dialogs.update { it.copy(draft = null, deleteDialog = dialog) }
        }
    }

    fun dismissDelete() = dialogs.update { it.copy(deleteDialog = null) }

    fun delete(method: PaymentMethod, moveTo: Long? = null) {
        viewModelScope.launch {
            when (val r = delete.invoke(method.id, moveTo)) {
                is PaymentMethodResult.Success -> dismissDelete()
                is PaymentMethodResult.Error -> dialogs.update { it.copy(deleteDialog = PaymentDeleteDialog.Blocked(method, r.error)) }
            }
        }
    }

    fun setArchived(method: PaymentMethod, archived: Boolean) {
        viewModelScope.launch {
            when (val r = archive(method.id, archived)) {
                is PaymentMethodResult.Success -> dialogs.update { it.copy(draft = null, deleteDialog = null) }
                is PaymentMethodResult.Error -> dialogs.update { it.copy(draft = null, deleteDialog = PaymentDeleteDialog.Blocked(method, r.error)) }
            }
        }
    }
}

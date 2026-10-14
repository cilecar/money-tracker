package app.moneytracker.ui.incomecategories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.data.db.DefaultNames
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.usecase.ArchiveIncomeCategoryUseCase
import app.moneytracker.domain.usecase.DeleteIncomeCategoryUseCase
import app.moneytracker.domain.usecase.IncomeCategoryError
import app.moneytracker.domain.usecase.IncomeCategoryResult
import app.moneytracker.domain.usecase.SaveIncomeCategoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IncomeCategoryDraft(val original: IncomeCategory? = null, val name: String = "", val error: IncomeCategoryError? = null)

sealed interface IncomeCategoryDeleteDialog {
    data class Confirm(val category: IncomeCategory) : IncomeCategoryDeleteDialog
    data class Move(val category: IncomeCategory, val count: Int, val targets: List<IncomeCategory>) : IncomeCategoryDeleteDialog
    data class Blocked(val category: IncomeCategory, val error: IncomeCategoryError) : IncomeCategoryDeleteDialog
}

data class IncomeCategoriesUiState(
    val active: List<IncomeCategory> = emptyList(),
    val archived: List<IncomeCategory> = emptyList(),
    val draft: IncomeCategoryDraft? = null,
    val deleteDialog: IncomeCategoryDeleteDialog? = null,
)

@HiltViewModel
class IncomeCategoriesViewModel @Inject constructor(
    private val repository: IncomeCategoryRepository,
    private val save: SaveIncomeCategoryUseCase,
    private val archive: ArchiveIncomeCategoryUseCase,
    private val delete: DeleteIncomeCategoryUseCase,
    private val defaultNames: DefaultNames,
) : ViewModel() {

    private val dialogs = MutableStateFlow(IncomeCategoriesUiState())

    val state: StateFlow<IncomeCategoriesUiState> = combine(repository.observeAll(includeArchived = true), dialogs) { all, d ->
        d.copy(active = all.filter { !it.archived }, archived = all.filter { it.archived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IncomeCategoriesUiState())

    fun openNew() = dialogs.update { it.copy(draft = IncomeCategoryDraft()) }

    fun openEdit(c: IncomeCategory) = dialogs.update { it.copy(draft = IncomeCategoryDraft(c, defaultNames.resolve(c.defaultKey, c.name))) }

    fun updateName(name: String) = dialogs.update { s -> s.copy(draft = s.draft?.copy(name = name, error = null)) }

    fun closeDraft() = dialogs.update { it.copy(draft = null) }

    fun saveDraft() {
        val d = dialogs.value.draft ?: return
        val original = d.original
        // Имя не меняли — сохраняем ключ, чтобы название по умолчанию переводилось при смене языка.
        val keepKey = original?.defaultKey != null && d.name.trim().equals(defaultNames.resolve(original.defaultKey, original.name), ignoreCase = true)
        val category = original?.copy(name = if (keepKey) original.name else d.name, defaultKey = original.defaultKey.takeIf { keepKey })
            ?: IncomeCategory(name = d.name, iconKey = "savings", color = 0xFF66BB6A.toInt())
        viewModelScope.launch {
            when (val r = save(category)) {
                is IncomeCategoryResult.Success -> closeDraft()
                is IncomeCategoryResult.Error -> dialogs.update { it.copy(draft = it.draft?.copy(error = r.error)) }
            }
        }
    }

    fun setArchived(c: IncomeCategory, archived: Boolean) {
        viewModelScope.launch {
            when (val r = archive(c.id, archived)) {
                is IncomeCategoryResult.Success -> dialogs.update { it.copy(draft = null, deleteDialog = null) }
                is IncomeCategoryResult.Error -> dialogs.update { it.copy(draft = null, deleteDialog = IncomeCategoryDeleteDialog.Blocked(c, r.error)) }
            }
        }
    }

    fun requestDelete(c: IncomeCategory) {
        viewModelScope.launch {
            val count = delete.usage(c.id)
            val dialog = if (count > 0) {
                IncomeCategoryDeleteDialog.Move(c, count, repository.getAll(includeArchived = false).filter { it.id != c.id })
            } else {
                IncomeCategoryDeleteDialog.Confirm(c)
            }
            dialogs.update { it.copy(draft = null, deleteDialog = dialog) }
        }
    }

    fun dismissDelete() = dialogs.update { it.copy(deleteDialog = null) }

    fun delete(c: IncomeCategory, moveTo: Long? = null) {
        viewModelScope.launch {
            when (val r = delete.invoke(c.id, moveTo)) {
                is IncomeCategoryResult.Success -> dismissDelete()
                is IncomeCategoryResult.Error -> dialogs.update { it.copy(deleteDialog = IncomeCategoryDeleteDialog.Blocked(c, r.error)) }
            }
        }
    }
}

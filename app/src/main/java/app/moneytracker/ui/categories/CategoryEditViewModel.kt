package app.moneytracker.ui.categories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CategoryUsage
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.usecase.ArchiveCategoryUseCase
import app.moneytracker.domain.usecase.CategoryError
import app.moneytracker.domain.usecase.CategoryResult
import app.moneytracker.domain.usecase.CategoryRules
import app.moneytracker.domain.usecase.DeleteCategoryUseCase
import app.moneytracker.domain.usecase.SaveCategoryUseCase
import app.moneytracker.ui.common.CategoryColors
import app.moneytracker.data.db.DefaultNames
import app.moneytracker.ui.navigation.CategoryEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Какой диалог удаления показать. */
sealed interface DeleteDialog {
    /** [removesBudget]: у категории есть бюджет — он удалится вместе с ней, предупреждаем. */
    data class Confirm(val removesBudget: Boolean) : DeleteDialog
    data class Move(val usage: CategoryUsage, val targets: List<Category>) : DeleteDialog
    data class Blocked(val error: CategoryError) : DeleteDialog
}

data class CategoryEditUiState(
    val loaded: Boolean = false,
    val original: Category? = null,
    val name: String = "",
    val iconKey: String = "more_horiz",
    val color: Int = CategoryColors.palette.first(),
    val parentId: Long? = null,
    /** Возможные родители: активные категории верхнего уровня, кроме себя. */
    val parents: List<Category> = emptyList(),
    /** Если есть подкатегории, саму категорию нельзя сделать подкатегорией. */
    val hasChildren: Boolean = false,
    val error: CategoryError? = null,
    val deleteDialog: DeleteDialog? = null,
) {
    val isNew: Boolean get() = original == null

    val preview: Category get() = Category(name = name, iconKey = iconKey, color = color)
}

@HiltViewModel
class CategoryEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CategoryRepository,
    private val saveCategory: SaveCategoryUseCase,
    private val deleteCategory: DeleteCategoryUseCase,
    private val archiveCategory: ArchiveCategoryUseCase,
    private val defaultNames: DefaultNames,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CategoryEditRoute>()

    private val _state = MutableStateFlow(CategoryEditUiState())
    val state: StateFlow<CategoryEditUiState> = _state

    private val _close = Channel<Unit>(Channel.CONFLATED)
    val close = _close.receiveAsFlow()

    init {
        viewModelScope.launch {
            val all = repository.getAll()
            val original = all.firstOrNull { it.id == route.id }
            val parents = all.filter { it.parentId == null && !it.archived && it.id != route.id }
            _state.value = if (original != null) {
                CategoryEditUiState(
                    loaded = true,
                    original = original,
                    name = defaultNames.resolve(original.defaultKey, original.name),
                    iconKey = original.iconKey,
                    color = original.color,
                    parentId = original.parentId,
                    parents = parents,
                    hasChildren = all.any { it.parentId == original.id },
                )
            } else {
                val parent = all.firstOrNull { it.id == route.parentId }
                // Новой категории — первый цвет палитры, который ещё никем не занят.
                val usedColors = all.map { it.color }.toSet()
                CategoryEditUiState(
                    loaded = true,
                    parentId = parent?.id,
                    iconKey = parent?.iconKey ?: "more_horiz",
                    color = parent?.color ?: (CategoryColors.palette.firstOrNull { it !in usedColors } ?: CategoryColors.palette.first()),
                    parents = parents,
                )
            }
        }
    }

    fun onName(name: String) = _state.update { it.copy(name = name, error = null) }
    fun onIcon(key: String) = _state.update { it.copy(iconKey = key) }
    fun onColor(color: Int) = _state.update { it.copy(color = color) }
    fun onParent(id: Long?) = _state.update { it.copy(parentId = id, error = null) }

    fun save() {
        val s = _state.value
        val original = s.original
        // Имя не меняли (совпадает с переводом) — оставляем defaultKey, чтобы название следовало за языком.
        val keepKey = original?.defaultKey != null &&
            CategoryRules.sameName(s.name, defaultNames.resolve(original.defaultKey, original.name))
        val category = Category(
            id = original?.id ?: 0,
            name = if (keepKey) original!!.name else s.name,
            defaultKey = if (keepKey) original!!.defaultKey else null,
            iconKey = s.iconKey,
            color = s.color,
            parentId = s.parentId,
            sortOrder = original?.sortOrder ?: 0,
            archived = original?.archived ?: false,
        )
        viewModelScope.launch {
            when (val result = saveCategory(category)) {
                is CategoryResult.Success -> _close.send(Unit)
                is CategoryResult.Error -> _state.update { it.copy(error = result.error) }
            }
        }
    }

    fun requestDelete() {
        val id = _state.value.original?.id ?: return
        viewModelScope.launch {
            val usage = deleteCategory.usage(id)
            val dialog = when {
                usage.children > 0 -> DeleteDialog.Blocked(CategoryError.HAS_CHILDREN)
                usage.needsTarget -> DeleteDialog.Move(usage, CategoryRules.moveTargets(id, repository.getAll()))
                else -> DeleteDialog.Confirm(removesBudget = usage.budgets > 0)
            }
            _state.update { it.copy(deleteDialog = dialog) }
        }
    }

    fun dismissDialog() = _state.update { it.copy(deleteDialog = null) }

    fun delete(moveTo: Long? = null) {
        val id = _state.value.original?.id ?: return
        viewModelScope.launch {
            when (val result = deleteCategory(id, moveTo)) {
                is CategoryResult.Success -> _close.send(Unit)
                is CategoryResult.Error -> _state.update { it.copy(deleteDialog = DeleteDialog.Blocked(result.error)) }
            }
        }
    }

    fun setArchived(archived: Boolean) {
        val id = _state.value.original?.id ?: return
        viewModelScope.launch {
            when (val result = archiveCategory(id, archived)) {
                is CategoryResult.Success -> _close.send(Unit)
                is CategoryResult.Error -> _state.update { it.copy(deleteDialog = DeleteDialog.Blocked(result.error)) }
            }
        }
    }
}

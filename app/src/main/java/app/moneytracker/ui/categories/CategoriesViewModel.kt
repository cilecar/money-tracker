package app.moneytracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.usecase.ArchiveCategoryUseCase
import app.moneytracker.domain.usecase.CategoryError
import app.moneytracker.domain.usecase.CategoryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryNode(val category: Category, val children: List<Category>)

data class CategoriesUiState(
    val loading: Boolean = true,
    val active: List<CategoryNode> = emptyList(),
    val archived: List<Category> = emptyList(),
    /** Почему не удалось вернуть категорию из архива. */
    val restoreError: CategoryError? = null,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    repository: CategoryRepository,
    private val archive: ArchiveCategoryUseCase,
) : ViewModel() {

    private val restoreError = MutableStateFlow<CategoryError?>(null)

    val state: StateFlow<CategoriesUiState> = combine(repository.observeAll(includeArchived = true), restoreError) { all, error ->
            val active = all.filter { !it.archived }
            CategoriesUiState(
                loading = false,
                active = active.filter { it.parentId == null }.map { parent ->
                    CategoryNode(parent, active.filter { it.parentId == parent.id })
                },
                archived = all.filter { it.archived },
                restoreError = error,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun restore(id: Long) {
        viewModelScope.launch {
            restoreError.value = (archive(id, archived = false) as? CategoryResult.Error)?.error
        }
    }

    fun dismissRestoreError() {
        restoreError.value = null
    }
}

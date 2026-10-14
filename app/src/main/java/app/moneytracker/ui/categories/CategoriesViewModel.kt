package app.moneytracker.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.usecase.ArchiveCategoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryNode(val category: Category, val children: List<Category>)

data class CategoriesUiState(
    val loading: Boolean = true,
    val active: List<CategoryNode> = emptyList(),
    val archived: List<Category> = emptyList(),
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    repository: CategoryRepository,
    private val archive: ArchiveCategoryUseCase,
) : ViewModel() {

    val state: StateFlow<CategoriesUiState> = repository.observeAll(includeArchived = true)
        .map { all ->
            val active = all.filter { !it.archived }
            CategoriesUiState(
                loading = false,
                active = active.filter { it.parentId == null }.map { parent ->
                    CategoryNode(parent, active.filter { it.parentId == parent.id })
                },
                archived = all.filter { it.archived },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun restore(id: Long) {
        viewModelScope.launch { archive(id, archived = false) }
    }
}

package app.moneytracker.ui.recurring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.RecurringRepository
import app.moneytracker.domain.usecase.SaveRecurringUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecurringListUiState(
    val loading: Boolean = true,
    val items: List<RecurringExpense> = emptyList(),
    val categories: Map<Long, Category> = emptyMap(),
)

@HiltViewModel
class RecurringListViewModel @Inject constructor(
    repository: RecurringRepository,
    categories: CategoryRepository,
    private val save: SaveRecurringUseCase,
) : ViewModel() {

    val state: StateFlow<RecurringListUiState> =
        combine(repository.observeAll(), categories.observeAll(includeArchived = true)) { items, cats ->
            RecurringListUiState(false, items, cats.associateBy { it.id })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringListUiState())

    /** Пауза/возобновление. Через SaveRecurring: при возобновлении сработают платежи на сегодня. */
    fun setActive(item: RecurringExpense, active: Boolean) {
        viewModelScope.launch { save(item.copy(active = active)) }
    }
}

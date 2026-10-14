package app.moneytracker.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.BudgetRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.ObserveBudgetsUseCase
import app.moneytracker.domain.usecase.SaveBudgetUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class BudgetDraft(
    val existing: Budget? = null,
    val categoryId: Long? = null,
    val amountText: String = "",
    val currency: CurrencyCode = CurrencyCode.RSD,
) {
    val parsed: Money? get() = Money.parse(amountText, currency)?.takeIf { it.minor > 0 }
    val valid: Boolean get() = categoryId != null && parsed != null
}

data class BudgetsUiState(
    val loading: Boolean = true,
    val month: YearMonth,
    val today: LocalDate,
    val items: List<BudgetProgress> = emptyList(),
    /** Категории, для которых бюджета ещё нет, — для выбора при добавлении. */
    val freeCategories: List<Category> = emptyList(),
    val allCategories: List<Category> = emptyList(),
    val draft: BudgetDraft? = null,
) {
    val canGoNext: Boolean get() = month.isBefore(YearMonth.from(today))
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetsViewModel @Inject constructor(
    observeBudgets: ObserveBudgetsUseCase,
    categories: CategoryRepository,
    private val budgets: BudgetRepository,
    private val saveBudget: SaveBudgetUseCase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now(clock))
    private val draft = MutableStateFlow<BudgetDraft?>(null)

    val state: StateFlow<BudgetsUiState> = combine(
        month.flatMapLatest { observeBudgets(it) },
        month,
        categories.observeAll(),
        draft,
    ) { items, m, cats, d ->
        val used = items.map { it.budget.categoryId }.toSet()
        BudgetsUiState(
            loading = false,
            month = m,
            today = LocalDate.now(clock),
            // Сначала превышенные и близкие к лимиту — они важнее.
            items = items.sortedByDescending { it.ratio },
            freeCategories = cats.filter { it.id !in used },
            allCategories = cats,
            draft = d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState(month = month.value, today = LocalDate.now(clock)))

    fun previousMonth() = month.update { it.minusMonths(1) }
    fun nextMonth() = month.update { if (it.isBefore(YearMonth.now(clock))) it.plusMonths(1) else it }

    fun openNew() {
        viewModelScope.launch {
            draft.value = BudgetDraft(currency = settings.settings.first().displayCurrency)
        }
    }

    fun openEdit(budget: Budget) {
        draft.value = BudgetDraft(
            existing = budget,
            categoryId = budget.categoryId,
            amountText = budget.limit.amount.stripTrailingZeros().toPlainString(),
            currency = budget.limit.currency,
        )
    }

    fun updateDraft(transform: (BudgetDraft) -> BudgetDraft) = draft.update { it?.let(transform) }
    fun closeDraft() { draft.value = null }

    fun save() {
        val d = draft.value ?: return
        val limit = d.parsed ?: return
        val categoryId = d.categoryId ?: return
        viewModelScope.launch {
            saveBudget(Budget(id = d.existing?.id ?: 0, categoryId = categoryId, limit = limit))
            draft.value = null
        }
    }

    fun delete() {
        val id = draft.value?.existing?.id ?: return
        viewModelScope.launch {
            budgets.delete(id)
            draft.value = null
        }
    }
}

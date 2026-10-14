package app.moneytracker.domain.fake

import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.BudgetRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.RecurringRepository
import app.moneytracker.domain.usecase.BudgetNotifier
import app.moneytracker.domain.usecase.CheckBudgetsUseCase
import app.moneytracker.domain.usecase.ObserveBudgetsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

class FakeBudgetRepository : BudgetRepository {
    val items = MutableStateFlow<List<Budget>>(emptyList())
    val alerts = mutableSetOf<Triple<Long, YearMonth, BudgetLevel>>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<Budget>> = items
    override suspend fun getAll() = items.value
    override suspend fun upsert(budget: Budget): Long {
        val existing = items.value.firstOrNull { it.categoryId == budget.categoryId }
        val id = existing?.id ?: if (budget.id != 0L) budget.id else nextId++
        items.value = items.value.filter { it.id != id } + budget.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { items.value = items.value.filter { it.id != id } }
    override suspend fun markAlerted(budgetId: Long, month: YearMonth, level: BudgetLevel) = alerts.add(Triple(budgetId, month, level))
}

class RecordingNotifier : BudgetNotifier {
    val sent = mutableListOf<Pair<Long, BudgetLevel>>()
    override fun notify(progress: BudgetProgress, level: BudgetLevel) { sent += progress.budget.id to level }
}

class FakeRecurringRepository(private val expenses: ExpenseRepository) : RecurringRepository {
    val items = MutableStateFlow<List<RecurringExpense>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<RecurringExpense>> = items
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun getDue(today: LocalDate) = items.value.filter { it.isDue(today) }
    override suspend fun upsert(recurring: RecurringExpense): Long {
        val id = if (recurring.id == 0L) nextId++ else recurring.id
        items.value = items.value.filter { it.id != id } + recurring.copy(id = id)
        return id
    }
    override suspend fun delete(id: Long) { items.value = items.value.filter { it.id != id } }
    override suspend fun recordOccurrence(
        expense: Expense,
        recurringId: Long,
        from: LocalDate,
        nextRunDate: LocalDate,
        active: Boolean,
    ): Boolean {
        if (!advance(recurringId, from, nextRunDate, active)) return false
        expenses.insert(expense)
        return true
    }

    override suspend fun advance(recurringId: Long, from: LocalDate, nextRunDate: LocalDate, active: Boolean): Boolean {
        val current = items.value.firstOrNull { it.id == recurringId && it.active && it.nextRunDate == from } ?: return false
        items.value = items.value.map { if (it === current) it.copy(nextRunDate = nextRunDate, active = active) else it }
        return true
    }
}

/** CheckBudgets на фейках — для тестов, где бюджеты не важны, но use case требует зависимость. */
fun checkBudgets(
    clock: Clock,
    expenses: ExpenseRepository = FakeExpenseRepository(),
    categories: CategoryRepository = FakeCategoryRepository(),
    budgets: FakeBudgetRepository = FakeBudgetRepository(),
    notifier: BudgetNotifier = RecordingNotifier(),
) = CheckBudgetsUseCase(ObserveBudgetsUseCase(budgets, expenses, categories), budgets, notifier, clock)


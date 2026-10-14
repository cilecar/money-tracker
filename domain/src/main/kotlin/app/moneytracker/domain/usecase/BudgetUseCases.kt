package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.repository.BudgetRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.RefundRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject

/** Отправка уведомления — интерфейс в домене, реализация (NotificationManager) в app. */
interface BudgetNotifier {
    fun notify(progress: BudgetProgress, level: BudgetLevel)
}

class ObserveBudgetsUseCase @Inject constructor(
    private val budgets: BudgetRepository,
    private val expenses: ExpenseRepository,
    private val categories: CategoryRepository,
    private val refunds: RefundRepository,
) {
    operator fun invoke(month: YearMonth): Flow<List<BudgetProgress>> = combine(
        budgets.observeAll(),
        expenses.observe(ExpenseFilter(from = month.atDay(1), to = month.atEndOfMonth())),
        categories.observeAll(includeArchived = true),
        refunds.observeBetween(month.atDay(1), month.atEndOfMonth()),
    ) { b, e, c, r -> BudgetProgress.compute(b, e, c.associateBy { it.id }, r) }
}

/**
 * Проверяет бюджеты текущего месяца и уведомляет о пересечении 80% и 100%.
 * Каждый порог — не больше одного уведомления за месяц (отметка в budget_alert).
 * Если сразу перескочили через оба порога — одно уведомление, о 100%.
 */
class CheckBudgetsUseCase @Inject constructor(
    private val observeBudgets: ObserveBudgetsUseCase,
    private val budgets: BudgetRepository,
    private val notifier: BudgetNotifier,
    private val clock: Clock,
) {
    suspend operator fun invoke() {
        val month = YearMonth.now(clock)
        for (progress in observeBudgets(month).first()) {
            val level = progress.level
            if (level == BudgetLevel.OK) continue
            val id = progress.budget.id
            val newExceeded = level == BudgetLevel.EXCEEDED && budgets.markAlerted(id, month, BudgetLevel.EXCEEDED)
            // 80% отмечаем в любом случае, чтобы после 100% не прилетело запоздалое «80%».
            val newWarning = budgets.markAlerted(id, month, BudgetLevel.WARNING)
            when {
                newExceeded -> notifier.notify(progress, BudgetLevel.EXCEEDED)
                newWarning -> notifier.notify(progress, BudgetLevel.WARNING)
            }
        }
    }
}

class SaveBudgetUseCase @Inject constructor(
    private val budgets: BudgetRepository,
    private val checkBudgets: CheckBudgetsUseCase,
) {
    suspend operator fun invoke(budget: Budget): Long {
        require(budget.limit.minor > 0) { "Budget limit must be positive" }
        val id = budgets.upsert(budget)
        // Новый лимит может оказаться уже превышенным — сообщим сразу.
        checkBudgets()
        return id
    }
}

package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import kotlinx.coroutines.flow.Flow
import java.time.YearMonth

interface BudgetRepository {
    fun observeAll(): Flow<List<Budget>>

    suspend fun getAll(): List<Budget>

    /** Один бюджет на категорию: повторное сохранение для той же категории заменяет лимит. */
    suspend fun upsert(budget: Budget): Long

    suspend fun delete(id: Long)

    /** Отмечает, что уведомление уровня [level] за [month] отправлено. false — уже было отмечено. */
    suspend fun markAlerted(budgetId: Long, month: YearMonth, level: BudgetLevel): Boolean
}

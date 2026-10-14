package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.RecurringExpense
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface RecurringRepository {
    fun observeAll(): Flow<List<RecurringExpense>>

    suspend fun get(id: Long): RecurringExpense?

    suspend fun getDue(today: LocalDate): List<RecurringExpense>

    suspend fun upsert(recurring: RecurringExpense): Long

    suspend fun delete(id: Long)

    /**
     * Атомарно: вставляет расход и сдвигает nextRunDate шаблона. Если процесс умрёт
     * посередине, не будет ни «расхода без сдвига» (дубль при следующем запуске),
     * ни «сдвига без расхода» (пропуск).
     */
    suspend fun recordOccurrence(expense: Expense, recurringId: Long, nextRunDate: LocalDate, active: Boolean)
}

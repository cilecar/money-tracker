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
     * Атомарно: вставляет расход и сдвигает nextRunDate шаблона с [from] на [nextRunDate]. Если процесс умрёт
     * посередине, не будет ни «расхода без сдвига» (дубль при следующем запуске),
     * ни «сдвига без расхода» (пропуск).
     *
     * Compare-and-set: только если шаблон всё ещё есть, активен и его nextRunDate == [from].
     * Иначе (удалён, на паузе, заменён импортом после чтения) ничего не пишет.
     * @return false — шаблон изменился, расход не создан.
     */
    suspend fun recordOccurrence(
        expense: Expense,
        recurringId: Long,
        from: LocalDate,
        nextRunDate: LocalDate,
        active: Boolean,
    ): Boolean

    /** Сдвиг без расхода (пропуск слишком старых дат), с той же проверкой, что [recordOccurrence]. */
    suspend fun advance(recurringId: Long, from: LocalDate, nextRunDate: LocalDate, active: Boolean): Boolean
}

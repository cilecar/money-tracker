package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseFilter
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface ExpenseRepository {
    fun observe(id: Long): Flow<Expense?>

    /** Расходы с localDate в [from, to] включительно, новые сверху. */
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<Expense>>

    /** Расходы по фильтру, новые сверху. categoryIds в фильтре уже должны включать подкатегории. */
    fun observe(filter: ExpenseFilter): Flow<List<Expense>>

    /** id категорий в порядке последнего использования — для чипов «недавние». */
    fun observeRecentCategoryIds(limit: Int): Flow<List<Long>>

    /** Все теги, самые используемые первыми — для подсказок при вводе. */
    fun observeTags(): Flow<List<String>>

    suspend fun get(id: Long): Expense?

    /** Расходы, добавленные, когда курсов ещё не было. */
    suspend fun getWithoutRates(): List<Expense>

    /** Вставка; если id != 0, запись восстанавливается с тем же id (нужно для undo). */
    suspend fun insert(expense: Expense): Long

    suspend fun update(expense: Expense)

    suspend fun delete(id: Long)
}

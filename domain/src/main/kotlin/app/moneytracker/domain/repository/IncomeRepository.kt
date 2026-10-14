package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Refund
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface IncomeRepository {
    /** Доходы с localDate в [from, to] (null — без границы), новые сверху. */
    fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Income>>

    suspend fun get(id: Long): Income?

    /** Если id != 0, запись восстанавливается с тем же id (undo). */
    suspend fun insert(income: Income): Long

    suspend fun update(income: Income)

    suspend fun delete(id: Long)

    suspend fun getWithoutRates(): List<Income>

    /** Как [ExpenseRepository.fillRates]: пишет только курсы и только если запись не менялась. */
    suspend fun fillRates(income: Income): Boolean
}

interface RefundRepository {
    fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Refund>>

    fun observeForExpense(expenseId: Long): Flow<List<Refund>>

    suspend fun get(id: Long): Refund?

    suspend fun insert(refund: Refund): Long

    suspend fun update(refund: Refund)

    suspend fun delete(id: Long)

    suspend fun getWithoutRates(): List<Refund>

    suspend fun fillRates(refund: Refund): Boolean
}

interface IncomeCategoryRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<IncomeCategory>>

    suspend fun getAll(includeArchived: Boolean = true): List<IncomeCategory>

    suspend fun upsert(category: IncomeCategory): Long

    suspend fun setArchived(id: Long, archived: Boolean)

    suspend fun countIncomes(id: Long): Int

    /** Переносит доходы в [moveTo] (если задан) и удаляет категорию. */
    suspend fun delete(id: Long, moveTo: Long?)

    suspend fun nextSortOrder(): Int
}

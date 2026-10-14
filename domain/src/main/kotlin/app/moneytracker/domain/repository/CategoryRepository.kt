package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CategoryUsage
import kotlinx.coroutines.flow.Flow

interface CategoryRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Category>>

    suspend fun getAll(includeArchived: Boolean = true): List<Category>

    suspend fun get(id: Long): Category?

    suspend fun upsert(category: Category): Long

    suspend fun setArchived(ids: Collection<Long>, archived: Boolean)

    suspend fun usage(id: Long): CategoryUsage

    /**
     * Одной транзакцией: переносит расходы и регулярные платежи в [moveTo] (если задан)
     * и удаляет категорию. Бюджет категории удаляется каскадно.
     */
    suspend fun delete(id: Long, moveTo: Long?)

    /** Порядковый номер для новой категории — в конец своего уровня. */
    suspend fun nextSortOrder(parentId: Long?): Int
}

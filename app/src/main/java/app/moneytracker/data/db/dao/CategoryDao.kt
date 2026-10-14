package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.moneytracker.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CategoryDao {
    @Query("SELECT * FROM category WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract fun observeAll(includeArchived: Boolean): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract suspend fun getAll(includeArchived: Boolean): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id")
    abstract suspend fun get(id: Long): CategoryEntity?

    @Upsert
    abstract suspend fun upsert(category: CategoryEntity): Long

    @Query("UPDATE category SET archived = :archived WHERE id IN (:ids)")
    abstract suspend fun setArchived(ids: List<Long>, archived: Boolean)

    @Query("SELECT COUNT(*) FROM expense WHERE category_id = :id")
    abstract suspend fun countExpenses(id: Long): Int

    @Query("SELECT COUNT(*) FROM recurring_expense WHERE category_id = :id")
    abstract suspend fun countRecurring(id: Long): Int

    @Query("SELECT COUNT(*) FROM budget WHERE category_id = :id")
    abstract suspend fun countBudgets(id: Long): Int

    @Query("SELECT COUNT(*) FROM category WHERE parent_id = :id")
    abstract suspend fun countChildren(id: Long): Int

    /** `IS` вместо `=`: так условие работает и для NULL (категории верхнего уровня). */
    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM category WHERE parent_id IS :parentId")
    abstract suspend fun nextSortOrder(parentId: Long?): Int

    /**
     * Перенос и удаление в одной транзакции: если что-то упадёт посередине,
     * не останется расходов, «перенесённых» в никуда.
     */
    @Transaction
    open suspend fun deleteMovingReferences(id: Long, moveTo: Long?) {
        if (moveTo != null) {
            moveExpenses(id, moveTo)
            moveRecurring(id, moveTo)
        }
        delete(id)
    }

    @Query("UPDATE expense SET category_id = :to WHERE category_id = :from")
    protected abstract suspend fun moveExpenses(from: Long, to: Long)

    @Query("UPDATE recurring_expense SET category_id = :to WHERE category_id = :from")
    protected abstract suspend fun moveRecurring(from: Long, to: Long)

    @Query("DELETE FROM category WHERE id = :id")
    protected abstract suspend fun delete(id: Long)
}

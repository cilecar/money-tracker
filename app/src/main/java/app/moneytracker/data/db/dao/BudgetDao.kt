package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.moneytracker.data.db.entity.BudgetAlertEntity
import app.moneytracker.data.db.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BudgetDao {
    @Query("SELECT * FROM budget ORDER BY id")
    abstract fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budget ORDER BY id")
    abstract suspend fun getAll(): List<BudgetEntity>

    @Query("SELECT * FROM budget WHERE category_id = :categoryId")
    abstract suspend fun getByCategory(categoryId: Long): BudgetEntity?

    @Query("SELECT * FROM budget WHERE id = :id")
    protected abstract suspend fun getById(id: Long): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(budget: BudgetEntity): Long

    @Query("UPDATE budget SET category_id = :categoryId, limit_minor = :limitMinor, currency = :currency WHERE id = :id")
    protected abstract suspend fun update(id: Long, categoryId: Long, limitMinor: Long, currency: String)

    @Query("DELETE FROM budget WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * Один бюджет на категорию (уникальный индекс). Существующий обновляем UPDATE'ом,
     * а не REPLACE: REPLACE удалил бы строку и каскадом стёр отметки об уведомлениях.
     */
    @Transaction
    open suspend fun save(budget: BudgetEntity): Long {
        // Обновляем только реально существующую строку. Бюджет с явным id, которого нет в базе
        // (импорт из JSON), — вставка с этим id.
        val existing = getByCategory(budget.categoryId)?.id
            ?: budget.id.takeIf { it != 0L && getById(it) != null }
        return if (existing != null) {
            update(existing, budget.categoryId, budget.limitMinor, budget.currency.name)
            existing
        } else {
            insert(budget)
        }
    }

    /** IGNORE + rowId: -1 значит «такая отметка уже была». */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAlert(alert: BudgetAlertEntity): Long
}

package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.moneytracker.data.db.entity.PaymentMethodEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PaymentMethodDao {
    @Query("SELECT * FROM payment_method WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract fun observeAll(includeArchived: Boolean): Flow<List<PaymentMethodEntity>>

    @Query("SELECT * FROM payment_method WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract suspend fun getAll(includeArchived: Boolean): List<PaymentMethodEntity>

    @Query("SELECT * FROM payment_method WHERE id = :id")
    abstract suspend fun get(id: Long): PaymentMethodEntity?

    @Upsert
    abstract suspend fun upsert(method: PaymentMethodEntity): Long

    @Query("UPDATE payment_method SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT COUNT(*) FROM expense WHERE payment_method_id = :id")
    abstract suspend fun countExpenses(id: Long): Int

    @Query("SELECT COUNT(*) FROM recurring_expense WHERE payment_method_id = :id")
    abstract suspend fun countRecurring(id: Long): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM payment_method")
    abstract suspend fun nextSortOrder(): Int

    @Transaction
    open suspend fun deleteMovingReferences(id: Long, moveTo: Long?) {
        if (moveTo != null) {
            moveExpenses(id, moveTo)
            moveRecurring(id, moveTo)
        }
        delete(id)
    }

    @Query("UPDATE expense SET payment_method_id = :to WHERE payment_method_id = :from")
    protected abstract suspend fun moveExpenses(from: Long, to: Long)

    @Query("UPDATE recurring_expense SET payment_method_id = :to WHERE payment_method_id = :from")
    protected abstract suspend fun moveRecurring(from: Long, to: Long)

    @Query("DELETE FROM payment_method WHERE id = :id")
    protected abstract suspend fun delete(id: Long)
}

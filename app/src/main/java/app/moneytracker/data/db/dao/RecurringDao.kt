package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
abstract class RecurringDao {
    @Query("SELECT * FROM recurring_expense ORDER BY active DESC, next_run_date")
    abstract fun observeAll(): Flow<List<RecurringExpenseEntity>>

    @Query("SELECT * FROM recurring_expense WHERE id = :id")
    abstract suspend fun get(id: Long): RecurringExpenseEntity?

    @Query("SELECT * FROM recurring_expense WHERE active = 1 AND next_run_date <= :today")
    abstract suspend fun getDue(today: LocalDate): List<RecurringExpenseEntity>

    @Upsert
    abstract suspend fun upsert(entity: RecurringExpenseEntity): Long

    @Query("DELETE FROM recurring_expense WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * Compare-and-set: сдвигаем, только если шаблон всё ещё активен и ждёт именно [from]
     * (его не удалили, не поставили на паузу и не перезаписали после чтения). @return 0 или 1.
     */
    @Query(
        """
        UPDATE recurring_expense SET next_run_date = :next, active = :active
        WHERE id = :id AND active = 1 AND next_run_date = :from
        """,
    )
    abstract suspend fun advance(id: Long, from: LocalDate, next: LocalDate, active: Boolean): Int
}

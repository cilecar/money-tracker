package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import app.moneytracker.data.db.entity.ExpenseEntity
import app.moneytracker.data.db.entity.ExpenseTagCrossRef
import app.moneytracker.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class ExpenseWithTags(
    @Embedded val expense: ExpenseEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(ExpenseTagCrossRef::class, parentColumn = "expense_id", entityColumn = "tag_id"),
    )
    val tags: List<TagEntity>,
)

@Dao
abstract class ExpenseDao {

    @Transaction
    @Query("SELECT * FROM expense WHERE id = :id")
    abstract fun observe(id: Long): Flow<ExpenseWithTags?>

    @Transaction
    @Query("SELECT * FROM expense WHERE id = :id")
    abstract suspend fun get(id: Long): ExpenseWithTags?

    @Transaction
    @Query(
        """
        SELECT * FROM expense
        WHERE local_date BETWEEN :from AND :to
        ORDER BY occurred_at DESC, id DESC
        """,
    )
    abstract fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<ExpenseWithTags>>

    /** Запрос строит [app.moneytracker.data.db.ExpenseQueries]. */
    @Transaction
    @RawQuery(observedEntities = [ExpenseEntity::class, ExpenseTagCrossRef::class, TagEntity::class])
    abstract fun observeRaw(query: SupportSQLiteQuery): Flow<List<ExpenseWithTags>>

    @Query(
        """
        SELECT category_id FROM expense
        GROUP BY category_id
        ORDER BY MAX(created_at) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentCategoryIds(limit: Int): Flow<List<Long>>

    @Query(
        """
        SELECT t.name FROM tag t JOIN expense_tag et ON et.tag_id = t.id
        GROUP BY t.id
        ORDER BY COUNT(*) DESC, t.name_key
        """,
    )
    abstract fun observeTags(): Flow<List<String>>

    @Transaction
    @Query("SELECT * FROM expense ORDER BY occurred_at, id")
    abstract suspend fun getAll(): List<ExpenseWithTags>

    @Transaction
    @Query("SELECT * FROM expense WHERE rate_date IS NULL")
    abstract suspend fun getWithoutRates(): List<ExpenseWithTags>

    @Query("DELETE FROM expense WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /** Вставка расхода и тегов одной транзакцией: либо всё, либо ничего. */
    @Transaction
    open suspend fun insertWithTags(expense: ExpenseEntity, tags: List<String>): Long {
        val id = insertEntity(expense)
        linkTags(id, tags)
        return id
    }

    @Transaction
    open suspend fun updateWithTags(expense: ExpenseEntity, tags: List<String>) {
        updateEntity(expense)
        clearTags(expense.id)
        linkTags(expense.id, tags)
    }

    private suspend fun linkTags(expenseId: Long, tags: List<String>) {
        if (tags.isEmpty()) return
        val refs = tags.map { name ->
            val tag = TagEntity(name = name)
            insertTag(tag)
            ExpenseTagCrossRef(expenseId, tagIdByKey(tag.nameKey))
        }
        insertCrossRefs(refs)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEntity(expense: ExpenseEntity): Long

    @Update
    protected abstract suspend fun updateEntity(expense: ExpenseEntity)

    @Query("DELETE FROM expense_tag WHERE expense_id = :expenseId")
    protected abstract suspend fun clearTags(expenseId: Long)

    /** IGNORE: если тег уже есть (по name_key), просто берём его id ниже. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT id FROM tag WHERE name_key = :key")
    protected abstract suspend fun tagIdByKey(key: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCrossRefs(refs: List<ExpenseTagCrossRef>)
}

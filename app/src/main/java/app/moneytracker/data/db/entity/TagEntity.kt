package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * «Кафе» и «кафе» — один тег. COLLATE NOCASE в SQLite сворачивает регистр только для ASCII,
 * поэтому уникальность держим по name_key = name.lowercase(), а name хранит исходное написание.
 */
@Entity(tableName = "tag", indices = [Index("name_key", unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String = name.lowercase(),
)

@Entity(
    tableName = "expense_tag",
    primaryKeys = ["expense_id", "tag_id"],
    foreignKeys = [
        ForeignKey(ExpenseEntity::class, ["id"], ["expense_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tag_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tag_id")],
)
data class ExpenseTagCrossRef(
    @ColumnInfo(name = "expense_id") val expenseId: Long,
    @ColumnInfo(name = "tag_id") val tagId: Long,
)

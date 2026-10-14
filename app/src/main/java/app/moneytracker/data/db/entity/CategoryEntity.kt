package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "category",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("parent_id")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "default_key") val defaultKey: String?,
    @ColumnInfo(name = "icon_key") val iconKey: String,
    val color: Int,
    @ColumnInfo(name = "parent_id") val parentId: Long?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
)

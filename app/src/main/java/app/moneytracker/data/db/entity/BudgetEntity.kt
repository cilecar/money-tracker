package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.CurrencyCode

@Entity(
    tableName = "budget",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("category_id", unique = true)],
)
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "limit_minor") val limitMinor: Long,
    val currency: CurrencyCode,
)

/** Отметка «уведомление о пороге [level]% за месяц [yearMonth] уже отправлено». */
@Entity(
    tableName = "budget_alert",
    primaryKeys = ["budget_id", "year_month", "level"],
    foreignKeys = [ForeignKey(BudgetEntity::class, ["id"], ["budget_id"], onDelete = ForeignKey.CASCADE)],
)
data class BudgetAlertEntity(
    @ColumnInfo(name = "budget_id") val budgetId: Long,
    /** Формат "2026-10". */
    @ColumnInfo(name = "year_month") val yearMonth: String,
    val level: Int,
)

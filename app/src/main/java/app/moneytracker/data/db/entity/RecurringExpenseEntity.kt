package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Frequency
import java.time.LocalDate

@Entity(
    tableName = "recurring_expense",
    foreignKeys = [
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("category_id"), Index("account_id"), Index("next_run_date")],
)
data class RecurringExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    val comment: String?,
    /** JSON-массив: теги шаблона нужны только при создании расхода, отдельная таблица избыточна. */
    val tags: List<String>,
    val frequency: Frequency,
    val interval: Int,
    @ColumnInfo(name = "day_of_month") val dayOfMonth: Int?,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate?,
    @ColumnInfo(name = "next_run_date") val nextRunDate: LocalDate,
    val active: Boolean,
)

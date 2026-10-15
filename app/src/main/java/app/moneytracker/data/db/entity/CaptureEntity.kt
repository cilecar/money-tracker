package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.CurrencyCode
import java.time.Instant

/** Покупка, захваченная из уведомления. Сырой текст уведомления здесь не хранится — только разобранные поля. */
@Entity(
    tableName = "captured_purchase",
    foreignKeys = [ForeignKey(ExpenseEntity::class, ["id"], ["expense_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("occurred_at"), Index("status"), Index("expense_id")],
)
data class CapturedPurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    val merchant: String,
    @ColumnInfo(name = "merchant_key") val merchantKey: String,
    @ColumnInfo(name = "card_suffix") val cardSuffix: String?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "notification_key") val notificationKey: String,
    /** PENDING / CONFIRMED / DISMISSED. */
    val status: String,
    @ColumnInfo(name = "expense_id") val expenseId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

/** Какую категорию пользователь выбрал для мерчанта в прошлый раз. */
@Entity(
    tableName = "merchant_rule",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("category_id")],
)
data class MerchantRuleEntity(
    @PrimaryKey @ColumnInfo(name = "merchant_key") val merchantKey: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

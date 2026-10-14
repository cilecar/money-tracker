package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.CurrencyCode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "expense",
    foreignKeys = [
        // RESTRICT: нельзя случайно удалить категорию со всей историей — только архивировать
        // или перенести расходы.
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(PaymentMethodEntity::class, ["id"], ["payment_method_id"], onDelete = ForeignKey.RESTRICT),
        // Удаление шаблона регулярного расхода не удаляет уже созданные расходы.
        ForeignKey(RecurringExpenseEntity::class, ["id"], ["recurring_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("local_date"),
        Index("category_id"),
        Index("payment_method_id"),
        Index("recurring_id"),
        Index("rate_date"),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "payment_method_id") val paymentMethodId: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    val comment: String?,
    // Снапшот курсов на момент добавления; все три NULL — курсов ещё не было
    @ColumnInfo(name = "rate_rsd_per_eur") val rateRsdPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_rub_per_eur") val rateRubPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_date") val rateDate: LocalDate?,
    // Денормализованные суммы во всех валютах (по снапшоту) — для SQL-агрегаций
    @ColumnInfo(name = "rsd_minor") val rsdMinor: Long?,
    @ColumnInfo(name = "eur_minor") val eurMinor: Long?,
    @ColumnInfo(name = "rub_minor") val rubMinor: Long?,
    @ColumnInfo(name = "recurring_id") val recurringId: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

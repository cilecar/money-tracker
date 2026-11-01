package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.CurrencyCode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val archived: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/**
 * Погашение — колонки в строке долга (частичных погашений нет). Валюта дублируется из счёта,
 * как у перевода: смена валюты счёта не исказит историю. Все колонки NULL — долг открыт
 * (Room тогда отдаёт null вместо объекта).
 */
data class SettlementColumns(
    @ColumnInfo(name = "settled_at") val settledAt: Instant,
    @ColumnInfo(name = "settled_local_date") val settledLocalDate: LocalDate,
    @ColumnInfo(name = "settle_account_id") val accountId: Long,
    @ColumnInfo(name = "settle_amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "settle_currency") val currency: CurrencyCode,
)

/**
 * «Мне должны»: доля в расходе. Валюту, категорию, дату и курс берёт у расхода — поэтому без своих колонок.
 * Удаляется вместе с расходом.
 */
@Entity(
    tableName = "expense_share",
    foreignKeys = [
        ForeignKey(ExpenseEntity::class, ["id"], ["expense_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(PersonEntity::class, ["id"], ["person_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["settle_account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("expense_id", "person_id", unique = true),
        Index("person_id"),
        Index("settle_account_id"),
        Index("settled_local_date"),
    ],
)
data class ExpenseShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "expense_id") val expenseId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
    /** В валюте расхода. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @Embedded val settlement: SettlementColumns?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/** «За меня заплатили»: расход без счёта. Колонки снапшота курса — как у [RefundEntity]. */
@Entity(
    tableName = "paid_for_me",
    foreignKeys = [
        ForeignKey(PersonEntity::class, ["id"], ["person_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["settle_account_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("person_id"),
        Index("local_date"),
        Index("category_id"),
        Index("rate_date"),
        Index("settle_account_id"),
        Index("settled_local_date"),
    ],
)
data class PaidForMeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    val comment: String?,
    @ColumnInfo(name = "rate_rsd_per_eur") val rateRsdPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_rub_per_eur") val rateRubPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_usdt_per_eur") val rateUsdtPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_date") val rateDate: LocalDate?,
    @ColumnInfo(name = "rsd_minor") val rsdMinor: Long?,
    @ColumnInfo(name = "eur_minor") val eurMinor: Long?,
    @ColumnInfo(name = "rub_minor") val rubMinor: Long?,
    @Embedded val settlement: SettlementColumns?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

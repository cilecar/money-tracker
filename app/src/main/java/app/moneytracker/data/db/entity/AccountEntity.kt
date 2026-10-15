package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.CurrencyCode
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "account")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    val currency: CurrencyCode,
    @ColumnInfo(name = "opening_balance_minor") val openingBalanceMinor: Long,
    @ColumnInfo(name = "opening_date") val openingDate: LocalDate,
    @ColumnInfo(name = "default_key") val defaultKey: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
    /** Последние 4 цифры карты — для автозахвата из уведомлений (версия 4). */
    @ColumnInfo(name = "card_suffix") val cardSuffix: String? = null,
)

@Entity(
    tableName = "balance_adjustment",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("account_id")],
)
data class BalanceAdjustmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    /** Со знаком, в валюте счёта. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    val comment: String?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

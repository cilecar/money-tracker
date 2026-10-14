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

/** Категории доходов — отдельный набор, чтобы бюджеты и аналитика расходов их не видели. */
@Entity(tableName = "income_category")
data class IncomeCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "default_key") val defaultKey: String?,
    @ColumnInfo(name = "icon_key") val iconKey: String,
    val color: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
)

/** Колонки снапшота курса и сумм по нему — как у [ExpenseEntity]. */
@Entity(
    tableName = "income",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(IncomeCategoryEntity::class, ["id"], ["income_category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("local_date"), Index("account_id"), Index("income_category_id"), Index("rate_date")],
)
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "income_category_id") val incomeCategoryId: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    val comment: String?,
    @ColumnInfo(name = "rate_rsd_per_eur") val rateRsdPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_rub_per_eur") val rateRubPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_date") val rateDate: LocalDate?,
    @ColumnInfo(name = "rsd_minor") val rsdMinor: Long?,
    @ColumnInfo(name = "eur_minor") val eurMinor: Long?,
    @ColumnInfo(name = "rub_minor") val rubMinor: Long?,
    /** Сколько зачислено на счёт в его валюте, если валюты дохода и счёта различаются. */
    @ColumnInfo(name = "account_amount_minor") val accountAmountMinor: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/**
 * Возврат по расходу. Категория копируется из исходного расхода: аналитика уменьшает траты категории,
 * даже если сам расход потом удалят (тогда expense_id станет NULL).
 */
@Entity(
    tableName = "refund",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(ExpenseEntity::class, ["id"], ["expense_id"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(CategoryEntity::class, ["id"], ["category_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("local_date"), Index("account_id"), Index("expense_id"), Index("category_id"), Index("rate_date")],
)
data class RefundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "expense_id") val expenseId: Long?,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    val comment: String?,
    @ColumnInfo(name = "rate_rsd_per_eur") val rateRsdPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_rub_per_eur") val rateRubPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_date") val rateDate: LocalDate?,
    @ColumnInfo(name = "rsd_minor") val rsdMinor: Long?,
    @ColumnInfo(name = "eur_minor") val eurMinor: Long?,
    @ColumnInfo(name = "rub_minor") val rubMinor: Long?,
    @ColumnInfo(name = "account_amount_minor") val accountAmountMinor: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

/**
 * Перевод между счетами. Обе суммы хранятся как есть: их отношение — фактический курс обменника.
 * Валюты дублируются из счетов, чтобы смена валюты счёта не исказила историю.
 */
@Entity(
    tableName = "transfer",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["from_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["to_account_id"], onDelete = ForeignKey.RESTRICT),
        // Комиссия — обычный расход в категории «Комиссии».
        ForeignKey(ExpenseEntity::class, ["id"], ["fee_expense_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("local_date"), Index("from_account_id"), Index("to_account_id"), Index("fee_expense_id")],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "from_account_id") val fromAccountId: Long,
    @ColumnInfo(name = "to_account_id") val toAccountId: Long,
    @ColumnInfo(name = "from_amount_minor") val fromAmountMinor: Long,
    @ColumnInfo(name = "from_currency") val fromCurrency: CurrencyCode,
    @ColumnInfo(name = "to_amount_minor") val toAmountMinor: Long,
    @ColumnInfo(name = "to_currency") val toCurrency: CurrencyCode,
    @ColumnInfo(name = "fee_expense_id") val feeExpenseId: Long?,
    @ColumnInfo(name = "occurred_at") val occurredAt: Instant,
    @ColumnInfo(name = "local_date") val localDate: LocalDate,
    val comment: String?,
    /** Курс API на момент перевода — только для сравнения с курсом обменника. */
    @ColumnInfo(name = "rate_rsd_per_eur") val rateRsdPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_rub_per_eur") val rateRubPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_date") val rateDate: LocalDate?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
)

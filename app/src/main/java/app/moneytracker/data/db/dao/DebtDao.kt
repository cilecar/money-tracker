package app.moneytracker.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import app.moneytracker.data.db.entity.ExpenseShareEntity
import app.moneytracker.data.db.entity.PaidForMeEntity
import app.moneytracker.data.db.entity.PersonEntity
import app.moneytracker.domain.model.CurrencyCode
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Dao
abstract class PersonDao {
    @Query("SELECT * FROM person WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract fun observeAll(includeArchived: Boolean): Flow<List<PersonEntity>>

    @Query("SELECT * FROM person ORDER BY id")
    abstract suspend fun getAll(): List<PersonEntity>

    @Query("SELECT * FROM person WHERE id = :id")
    abstract suspend fun get(id: Long): PersonEntity?

    @Upsert
    abstract suspend fun upsert(person: PersonEntity): Long

    @Query("UPDATE person SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM person")
    abstract suspend fun nextSortOrder(): Int
}

/**
 * Доля вместе с тем, что она берёт у расхода: валюта суммы, категория, дата, комментарий, счёт.
 * Своих колонок для этого у доли нет — поэтому доли читаются только через JOIN с расходом.
 */
data class ShareRow(
    @Embedded val share: ExpenseShareEntity,
    @ColumnInfo(name = "e_currency") val expenseCurrency: CurrencyCode,
    @ColumnInfo(name = "e_category_id") val expenseCategoryId: Long,
    @ColumnInfo(name = "e_local_date") val expenseLocalDate: LocalDate,
    @ColumnInfo(name = "e_comment") val expenseComment: String?,
    @ColumnInfo(name = "e_account_id") val expenseAccountId: Long,
)

@Dao
abstract class DebtDao {
    // --- Доли ---

    @Query("$SHARE_ROW WHERE s.expense_id = :expenseId ORDER BY s.id")
    abstract suspend fun sharesForExpense(expenseId: Long): List<ShareRow>

    @Query("$SHARE_ROW WHERE s.expense_id = :expenseId ORDER BY s.id")
    abstract fun observeSharesForExpense(expenseId: Long): Flow<List<ShareRow>>

    /** Границы — epochDay даты **расхода**; без границы — Long.MIN_VALUE / Long.MAX_VALUE. */
    @Query("$SHARE_ROW WHERE e.local_date BETWEEN :from AND :to ORDER BY s.id")
    abstract fun observeSharesBetween(from: Long, to: Long): Flow<List<ShareRow>>

    /** personId 0 — все люди. */
    @Query("$SHARE_ROW WHERE :personId = 0 OR s.person_id = :personId ORDER BY e.local_date DESC, s.id DESC")
    abstract fun observeShareDebts(personId: Long): Flow<List<ShareRow>>

    @Query("$SHARE_ROW WHERE s.settled_local_date BETWEEN :from AND :to ORDER BY s.settled_at DESC, s.id DESC")
    abstract fun observeSettledShares(from: Long, to: Long): Flow<List<ShareRow>>

    @Query("$SHARE_ROW WHERE s.id = :id")
    abstract suspend fun shareRow(id: Long): ShareRow?

    @Query("SELECT * FROM expense_share ORDER BY id")
    abstract suspend fun getAllShares(): List<ExpenseShareEntity>

    @Insert
    abstract suspend fun insertShares(shares: List<ExpenseShareEntity>)

    @Query("DELETE FROM expense_share WHERE expense_id = :expenseId AND settled_at IS NULL")
    abstract suspend fun deleteUnsettledShares(expenseId: Long)

    /** Compare-and-set: погашает, только если доля ещё открыта. @return 1 — погашено. */
    @Query(
        """
        UPDATE expense_share SET settled_at = :at, settled_local_date = :date, settle_account_id = :accountId,
            settle_amount_minor = :amountMinor, settle_currency = :currency, updated_at = :now
        WHERE id = :id AND settled_at IS NULL
        """,
    )
    abstract suspend fun settleShare(id: Long, at: Instant, date: LocalDate, accountId: Long, amountMinor: Long, currency: CurrencyCode, now: Instant): Int

    @Query(
        """
        UPDATE expense_share SET settled_at = NULL, settled_local_date = NULL, settle_account_id = NULL,
            settle_amount_minor = NULL, settle_currency = NULL, updated_at = :now
        WHERE id = :id
        """,
    )
    abstract suspend fun unsettleShare(id: Long, now: Instant)

    // --- «За меня заплатили» ---

    @Query("SELECT * FROM paid_for_me WHERE local_date BETWEEN :from AND :to ORDER BY occurred_at DESC, id DESC")
    abstract fun observePaidForMeBetween(from: Long, to: Long): Flow<List<PaidForMeEntity>>

    /** personId 0 — все люди. */
    @Query("SELECT * FROM paid_for_me WHERE :personId = 0 OR person_id = :personId ORDER BY local_date DESC, id DESC")
    abstract fun observePaidForMeDebts(personId: Long): Flow<List<PaidForMeEntity>>

    @Query("SELECT * FROM paid_for_me WHERE settled_local_date BETWEEN :from AND :to ORDER BY settled_at DESC, id DESC")
    abstract fun observeSettledPaidForMe(from: Long, to: Long): Flow<List<PaidForMeEntity>>

    @Query("SELECT * FROM paid_for_me WHERE id = :id")
    abstract suspend fun getPaidForMe(id: Long): PaidForMeEntity?

    @Query("SELECT * FROM paid_for_me ORDER BY id")
    abstract suspend fun getAllPaidForMe(): List<PaidForMeEntity>

    @Insert
    abstract suspend fun insertPaidForMe(entity: PaidForMeEntity): Long

    @Update
    abstract suspend fun updatePaidForMe(entity: PaidForMeEntity)

    @Query("DELETE FROM paid_for_me WHERE id = :id")
    abstract suspend fun deletePaidForMe(id: Long)

    @Query("SELECT * FROM paid_for_me WHERE rate_date IS NULL")
    abstract suspend fun getPaidForMeWithoutRates(): List<PaidForMeEntity>

    /** Compare-and-set, как [ExpenseDao.fillRates]. */
    @Query(
        """
        UPDATE paid_for_me SET
            rate_rsd_per_eur = :rsdPerEur, rate_rub_per_eur = :rubPerEur, rate_usdt_per_eur = :usdtPerEur, rate_date = :rateDate,
            rsd_minor = :rsdMinor, eur_minor = :eurMinor, rub_minor = :rubMinor, updated_at = :updatedAt
        WHERE id = :id AND rate_date IS NULL AND amount_minor = :amountMinor AND currency = :currency
        """,
    )
    abstract suspend fun fillPaidForMeRates(
        id: Long,
        amountMinor: Long,
        currency: String,
        rsdPerEur: BigDecimal,
        rubPerEur: BigDecimal,
        usdtPerEur: BigDecimal?,
        rateDate: LocalDate,
        rsdMinor: Long,
        eurMinor: Long,
        rubMinor: Long,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE paid_for_me SET settled_at = :at, settled_local_date = :date, settle_account_id = :accountId,
            settle_amount_minor = :amountMinor, settle_currency = :currency, updated_at = :now
        WHERE id = :id AND settled_at IS NULL
        """,
    )
    abstract suspend fun settlePaidForMe(id: Long, at: Instant, date: LocalDate, accountId: Long, amountMinor: Long, currency: CurrencyCode, now: Instant): Int

    @Query(
        """
        UPDATE paid_for_me SET settled_at = NULL, settled_local_date = NULL, settle_account_id = NULL,
            settle_amount_minor = NULL, settle_currency = NULL, updated_at = :now
        WHERE id = :id
        """,
    )
    abstract suspend fun unsettlePaidForMe(id: Long, now: Instant)

    private companion object {
        const val SHARE_ROW = """
            SELECT s.*, e.currency AS e_currency, e.category_id AS e_category_id, e.local_date AS e_local_date,
                e.comment AS e_comment, e.account_id AS e_account_id
            FROM expense_share s JOIN expense e ON e.id = s.expense_id
        """
    }
}

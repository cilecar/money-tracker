package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.moneytracker.data.db.entity.IncomeCategoryEntity
import app.moneytracker.data.db.entity.IncomeEntity
import app.moneytracker.data.db.entity.RefundEntity
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Dao
abstract class IncomeDao {
    /** Границы — epochDay; без границы передаём Long.MIN_VALUE / Long.MAX_VALUE. */
    @Query("SELECT * FROM income WHERE local_date BETWEEN :from AND :to ORDER BY occurred_at DESC, id DESC")
    abstract fun observeBetween(from: Long, to: Long): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM income ORDER BY occurred_at DESC, id DESC")
    abstract suspend fun getAll(): List<IncomeEntity>

    @Query("SELECT * FROM income WHERE id = :id")
    abstract suspend fun get(id: Long): IncomeEntity?

    @Insert
    abstract suspend fun insert(income: IncomeEntity): Long

    @Update
    abstract suspend fun update(income: IncomeEntity)

    @Query("DELETE FROM income WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM income WHERE rate_date IS NULL")
    abstract suspend fun getWithoutRates(): List<IncomeEntity>

    /** Compare-and-set, как [ExpenseDao.fillRates]. */
    @Query(
        """
        UPDATE income SET
            rate_rsd_per_eur = :rsdPerEur, rate_rub_per_eur = :rubPerEur, rate_usdt_per_eur = :usdtPerEur, rate_date = :rateDate,
            rsd_minor = :rsdMinor, eur_minor = :eurMinor, rub_minor = :rubMinor, updated_at = :updatedAt
        WHERE id = :id AND rate_date IS NULL AND amount_minor = :amountMinor AND currency = :currency
        """,
    )
    abstract suspend fun fillRates(
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
}

@Dao
abstract class RefundDao {
    @Query("SELECT * FROM refund WHERE local_date BETWEEN :from AND :to ORDER BY occurred_at DESC, id DESC")
    abstract fun observeBetween(from: Long, to: Long): Flow<List<RefundEntity>>

    @Query("SELECT * FROM refund WHERE expense_id = :expenseId ORDER BY occurred_at DESC, id DESC")
    abstract fun observeForExpense(expenseId: Long): Flow<List<RefundEntity>>

    @Query("SELECT * FROM refund ORDER BY occurred_at DESC, id DESC")
    abstract suspend fun getAll(): List<RefundEntity>

    @Query("SELECT * FROM refund WHERE id = :id")
    abstract suspend fun get(id: Long): RefundEntity?

    @Insert
    abstract suspend fun insert(refund: RefundEntity): Long

    @Update
    abstract suspend fun update(refund: RefundEntity)

    @Query("DELETE FROM refund WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM refund WHERE rate_date IS NULL")
    abstract suspend fun getWithoutRates(): List<RefundEntity>

    @Query(
        """
        UPDATE refund SET
            rate_rsd_per_eur = :rsdPerEur, rate_rub_per_eur = :rubPerEur, rate_usdt_per_eur = :usdtPerEur, rate_date = :rateDate,
            rsd_minor = :rsdMinor, eur_minor = :eurMinor, rub_minor = :rubMinor, updated_at = :updatedAt
        WHERE id = :id AND rate_date IS NULL AND amount_minor = :amountMinor AND currency = :currency
        """,
    )
    abstract suspend fun fillRates(
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
}

@Dao
abstract class IncomeCategoryDao {
    @Query("SELECT * FROM income_category WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract fun observeAll(includeArchived: Boolean): Flow<List<IncomeCategoryEntity>>

    @Query("SELECT * FROM income_category WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract suspend fun getAll(includeArchived: Boolean): List<IncomeCategoryEntity>

    @Upsert
    abstract suspend fun upsert(category: IncomeCategoryEntity): Long

    @Query("UPDATE income_category SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    @Query("SELECT COUNT(*) FROM income WHERE income_category_id = :id")
    abstract suspend fun countIncomes(id: Long): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM income_category")
    abstract suspend fun nextSortOrder(): Int

    @Transaction
    open suspend fun deleteMovingIncomes(id: Long, moveTo: Long?) {
        if (moveTo != null) moveIncomes(id, moveTo)
        delete(id)
    }

    @Query("UPDATE income SET income_category_id = :to WHERE income_category_id = :from")
    protected abstract suspend fun moveIncomes(from: Long, to: Long)

    @Query("DELETE FROM income_category WHERE id = :id")
    protected abstract suspend fun delete(id: Long)
}

@Dao
abstract class TransferDao {
    @Query("SELECT * FROM transfer WHERE local_date BETWEEN :from AND :to ORDER BY occurred_at DESC, id DESC")
    abstract fun observeBetween(from: Long, to: Long): Flow<List<app.moneytracker.data.db.entity.TransferEntity>>

    @Query("SELECT * FROM transfer ORDER BY occurred_at DESC, id DESC")
    abstract suspend fun getAll(): List<app.moneytracker.data.db.entity.TransferEntity>

    @Query("SELECT * FROM transfer WHERE id = :id")
    abstract suspend fun get(id: Long): app.moneytracker.data.db.entity.TransferEntity?

    @Upsert
    abstract suspend fun upsert(transfer: app.moneytracker.data.db.entity.TransferEntity): Long

    @Query("DELETE FROM transfer WHERE id = :id")
    abstract suspend fun delete(id: Long)
}

package app.moneytracker.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.moneytracker.data.db.entity.AccountEntity
import app.moneytracker.data.db.entity.BalanceAdjustmentEntity
import app.moneytracker.domain.model.CurrencyCode
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalDate

/** Остаток счёта в его валюте; [unconverted] — операции в чужой валюте без курса и суммы списания. */
data class AccountBalanceRow(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "balance_minor") val balanceMinor: Long,
    val unconverted: Int,
)

/** Операция USDT-счёта, которую надо пересчитать в USDT по её снапшоту, см. [AccountDao.observeUsdtConversions]. */
data class UsdtConversionRow(
    @ColumnInfo(name = "account_id") val accountId: Long,
    val sign: Int,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val currency: CurrencyCode,
    @ColumnInfo(name = "rate_rsd_per_eur") val rateRsdPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_rub_per_eur") val rateRubPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_usdt_per_eur") val rateUsdtPerEur: BigDecimal?,
    @ColumnInfo(name = "rate_date") val rateDate: LocalDate?,
)

@Dao
abstract class AccountDao {
    @Query("SELECT * FROM account WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract fun observeAll(includeArchived: Boolean): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE archived = 0 OR :includeArchived ORDER BY sort_order, id")
    abstract suspend fun getAll(includeArchived: Boolean): List<AccountEntity>

    @Query("SELECT * FROM account WHERE id = :id")
    abstract suspend fun get(id: Long): AccountEntity?

    @Upsert
    abstract suspend fun upsert(account: AccountEntity): Long

    @Query("UPDATE account SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: Long, archived: Boolean)

    /**
     * Остатки всех счетов одним запросом, баланс нигде не хранится:
     * начальный остаток + корректировки − расходы + доходы + возвраты ± переводы.
     * Суммы перевода уже в валютах своих счетов — пересчёт не нужен.
     *
     * Сумма операции в валюте счёта: сумма списания, если записана; иначе сама сумма, если валюта совпадает;
     * иначе пересчёт по снапшоту курса операции (rsd/eur/rub_minor). Без снапшота CASE даёт NULL —
     * SUM его пропускает, а такие операции считаются в unconverted.
     *
     * У USDT-счёта готовой колонки usdt_minor нет: такие операции CASE тоже пропускает, но в unconverted
     * не считает — их пересчитывает по снапшоту [observeUsdtConversions].
     * Операции раньше opening_date не учитываются: они уже вошли в начальный остаток.
     */
    @Query(
        """
        SELECT a.id AS account_id,
            a.opening_balance_minor
            + COALESCE((SELECT SUM(b.amount_minor) FROM balance_adjustment b
                WHERE b.account_id = a.id AND b.local_date >= a.opening_date), 0)
            - COALESCE((SELECT SUM(CASE
                    WHEN e.account_amount_minor IS NOT NULL THEN e.account_amount_minor
                    WHEN e.currency = a.currency THEN e.amount_minor
                    WHEN a.currency = 'RSD' THEN e.rsd_minor
                    WHEN a.currency = 'EUR' THEN e.eur_minor
                    WHEN a.currency = 'RUB' THEN e.rub_minor
                END) FROM expense e
                WHERE e.account_id = a.id AND e.local_date >= a.opening_date), 0)
            + COALESCE((SELECT SUM(CASE
                    WHEN i.account_amount_minor IS NOT NULL THEN i.account_amount_minor
                    WHEN i.currency = a.currency THEN i.amount_minor
                    WHEN a.currency = 'RSD' THEN i.rsd_minor
                    WHEN a.currency = 'EUR' THEN i.eur_minor
                    WHEN a.currency = 'RUB' THEN i.rub_minor
                END) FROM income i
                WHERE i.account_id = a.id AND i.local_date >= a.opening_date), 0)
            + COALESCE((SELECT SUM(CASE
                    WHEN r.account_amount_minor IS NOT NULL THEN r.account_amount_minor
                    WHEN r.currency = a.currency THEN r.amount_minor
                    WHEN a.currency = 'RSD' THEN r.rsd_minor
                    WHEN a.currency = 'EUR' THEN r.eur_minor
                    WHEN a.currency = 'RUB' THEN r.rub_minor
                END) FROM refund r
                WHERE r.account_id = a.id AND r.local_date >= a.opening_date), 0)
            + COALESCE((SELECT SUM(t.to_amount_minor) FROM transfer t
                WHERE t.to_account_id = a.id AND t.local_date >= a.opening_date), 0)
            - COALESCE((SELECT SUM(t.from_amount_minor) FROM transfer t
                WHERE t.from_account_id = a.id AND t.local_date >= a.opening_date), 0)
            AS balance_minor,
            (SELECT COUNT(*) FROM expense e
                WHERE e.account_id = a.id AND e.local_date >= a.opening_date
                AND e.account_amount_minor IS NULL AND e.currency <> a.currency AND e.rsd_minor IS NULL
                AND a.currency <> 'USDT')
            + (SELECT COUNT(*) FROM income i
                WHERE i.account_id = a.id AND i.local_date >= a.opening_date
                AND i.account_amount_minor IS NULL AND i.currency <> a.currency AND i.rsd_minor IS NULL
                AND a.currency <> 'USDT')
            + (SELECT COUNT(*) FROM refund r
                WHERE r.account_id = a.id AND r.local_date >= a.opening_date
                AND r.account_amount_minor IS NULL AND r.currency <> a.currency AND r.rsd_minor IS NULL
                AND a.currency <> 'USDT')
            AS unconverted
        FROM account a
        """,
    )
    abstract fun observeBalances(): Flow<List<AccountBalanceRow>>

    /**
     * Операции USDT-счёта в другой валюте без суммы списания — для пересчёта по снапшоту в коде
     * (курс хранится текстом ради точности, в SQL его не посчитать без потерь). [UsdtConversionRow.sign]:
     * −1 расход, +1 доход и возврат. Переводы сюда не попадают: их суммы уже в валютах счетов.
     */
    @Query(
        """
        SELECT a.id AS account_id, -1 AS sign, e.amount_minor, e.currency, e.rate_rsd_per_eur, e.rate_rub_per_eur,
            e.rate_usdt_per_eur, e.rate_date
        FROM expense e JOIN account a ON a.id = e.account_id
        WHERE a.currency = 'USDT' AND e.currency <> 'USDT' AND e.account_amount_minor IS NULL
            AND e.local_date >= a.opening_date
        UNION ALL
        SELECT a.id, 1, i.amount_minor, i.currency, i.rate_rsd_per_eur, i.rate_rub_per_eur, i.rate_usdt_per_eur, i.rate_date
        FROM income i JOIN account a ON a.id = i.account_id
        WHERE a.currency = 'USDT' AND i.currency <> 'USDT' AND i.account_amount_minor IS NULL
            AND i.local_date >= a.opening_date
        UNION ALL
        SELECT a.id, 1, r.amount_minor, r.currency, r.rate_rsd_per_eur, r.rate_rub_per_eur, r.rate_usdt_per_eur, r.rate_date
        FROM refund r JOIN account a ON a.id = r.account_id
        WHERE a.currency = 'USDT' AND r.currency <> 'USDT' AND r.account_amount_minor IS NULL
            AND r.local_date >= a.opening_date
        """,
    )
    abstract fun observeUsdtConversions(): Flow<List<UsdtConversionRow>>

    @Insert
    abstract suspend fun insertAdjustment(adjustment: BalanceAdjustmentEntity): Long

    @Query("SELECT * FROM balance_adjustment WHERE account_id = :accountId ORDER BY occurred_at DESC, id DESC")
    abstract fun observeAdjustments(accountId: Long): Flow<List<BalanceAdjustmentEntity>>

    @Query("DELETE FROM balance_adjustment WHERE id = :id")
    abstract suspend fun deleteAdjustment(id: Long)

    @Query("SELECT COUNT(*) FROM expense WHERE account_id = :id")
    abstract suspend fun countExpenses(id: Long): Int

    @Query("SELECT COUNT(*) FROM recurring_expense WHERE account_id = :id")
    abstract suspend fun countRecurring(id: Long): Int

    @Query("SELECT COUNT(*) FROM balance_adjustment WHERE account_id = :id")
    abstract suspend fun countAdjustments(id: Long): Int

    @Query("SELECT (SELECT COUNT(*) FROM income WHERE account_id = :id) + (SELECT COUNT(*) FROM refund WHERE account_id = :id)")
    abstract suspend fun countIncomesAndRefunds(id: Long): Int

    @Query("SELECT COUNT(*) FROM transfer WHERE from_account_id = :id OR to_account_id = :id")
    abstract suspend fun countTransfers(id: Long): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM account")
    abstract suspend fun nextSortOrder(): Int

    /** Есть ли у счёта операции — тогда валюту менять нельзя, иначе прошлые остатки поплывут. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM expense WHERE account_id = :id) OR EXISTS(SELECT 1 FROM balance_adjustment WHERE account_id = :id) " +
            "OR EXISTS(SELECT 1 FROM income WHERE account_id = :id) OR EXISTS(SELECT 1 FROM refund WHERE account_id = :id) " +
            "OR EXISTS(SELECT 1 FROM transfer WHERE from_account_id = :id OR to_account_id = :id)",
    )
    abstract suspend fun hasOperations(id: Long): Boolean

    /**
     * Корректировки сверки удаляются вместе со счётом: они описывают его остаток, на другом счёте
     * они бессмысленны. Расходы, доходы, возвраты и регулярные платежи переносятся.
     */
    @Transaction
    open suspend fun deleteMovingReferences(id: Long, moveTo: Long?) {
        if (moveTo != null) {
            moveExpenses(id, moveTo)
            moveIncomes(id, moveTo)
            moveRefunds(id, moveTo)
            moveRecurring(id, moveTo)
        }
        deleteAdjustments(id)
        delete(id)
    }

    // Сумма списания относится к прежнему счёту (его валюте) — при переносе она теряет смысл.
    @Query("UPDATE expense SET account_id = :to, account_amount_minor = NULL WHERE account_id = :from")
    protected abstract suspend fun moveExpenses(from: Long, to: Long)

    @Query("UPDATE income SET account_id = :to, account_amount_minor = NULL WHERE account_id = :from")
    protected abstract suspend fun moveIncomes(from: Long, to: Long)

    @Query("UPDATE refund SET account_id = :to, account_amount_minor = NULL WHERE account_id = :from")
    protected abstract suspend fun moveRefunds(from: Long, to: Long)

    @Query("UPDATE recurring_expense SET account_id = :to WHERE account_id = :from")
    protected abstract suspend fun moveRecurring(from: Long, to: Long)

    @Query("DELETE FROM balance_adjustment WHERE account_id = :id")
    protected abstract suspend fun deleteAdjustments(id: Long)

    @Query("DELETE FROM account WHERE id = :id")
    protected abstract suspend fun delete(id: Long)
}

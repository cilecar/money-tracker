package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import app.moneytracker.data.db.entity.CapturedPurchaseEntity
import app.moneytracker.data.db.entity.MerchantRuleEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
abstract class CaptureDao {
    @Query("SELECT * FROM captured_purchase WHERE status = 'PENDING' ORDER BY occurred_at DESC")
    abstract fun observePending(): Flow<List<CapturedPurchaseEntity>>

    @Query("SELECT * FROM captured_purchase WHERE id = :id")
    abstract suspend fun get(id: Long): CapturedPurchaseEntity?

    @Query("SELECT * FROM captured_purchase WHERE occurred_at BETWEEN :from AND :to")
    abstract suspend fun getBetween(from: Instant, to: Instant): List<CapturedPurchaseEntity>

    @Insert
    abstract suspend fun insert(purchase: CapturedPurchaseEntity): Long

    @Query("UPDATE captured_purchase SET status = :status, expense_id = COALESCE(:expenseId, expense_id) WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String, expenseId: Long?)

    @Query("SELECT category_id FROM merchant_rule WHERE merchant_key = :key")
    abstract suspend fun ruleFor(key: String): Long?

    @Upsert
    abstract suspend fun saveRule(rule: MerchantRuleEntity)

    @Query("SELECT * FROM merchant_rule")
    abstract suspend fun allRules(): List<MerchantRuleEntity>
}

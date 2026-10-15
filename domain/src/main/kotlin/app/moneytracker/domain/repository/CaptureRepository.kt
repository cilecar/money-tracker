package app.moneytracker.domain.repository

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface CaptureRepository {
    fun observePending(): Flow<List<CapturedPurchase>>

    suspend fun get(id: Long): CapturedPurchase?

    /** Захваченные (любого статуса) с occurredAt в [from, to] — для поиска дублей. */
    suspend fun getBetween(from: Instant, to: Instant): List<CapturedPurchase>

    suspend fun insert(purchase: CapturedPurchase): Long

    suspend fun setStatus(id: Long, status: CaptureStatus, expenseId: Long? = null)

    /** Категория, выбранная для мерчанта в прошлый раз. */
    suspend fun ruleFor(merchantKey: String): Long?

    suspend fun saveRule(merchantKey: String, categoryId: Long)
}

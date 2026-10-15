package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.CaptureDao
import app.moneytracker.data.db.entity.MerchantRuleEntity
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.repository.CaptureRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

class CaptureRepositoryImpl @Inject constructor(
    private val dao: CaptureDao,
    private val clock: Clock,
) : CaptureRepository {
    override fun observePending(): Flow<List<CapturedPurchase>> = dao.observePending().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long) = dao.get(id)?.toDomain()

    override suspend fun getBetween(from: Instant, to: Instant) = dao.getBetween(from, to).map { it.toDomain() }

    override suspend fun insert(purchase: CapturedPurchase): Long = dao.insert(purchase.toEntity())

    override suspend fun setStatus(id: Long, status: CaptureStatus, expenseId: Long?) = dao.setStatus(id, status.name, expenseId)

    override suspend fun ruleFor(merchantKey: String) = dao.ruleFor(merchantKey)

    override suspend fun saveRule(merchantKey: String, categoryId: Long) =
        dao.saveRule(MerchantRuleEntity(merchantKey, categoryId, clock.instant()))
}

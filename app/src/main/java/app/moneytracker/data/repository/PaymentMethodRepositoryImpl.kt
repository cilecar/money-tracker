package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.PaymentMethodDao
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.PaymentMethodUsage
import app.moneytracker.domain.repository.PaymentMethodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class PaymentMethodRepositoryImpl @Inject constructor(
    private val dao: PaymentMethodDao,
) : PaymentMethodRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<PaymentMethod>> =
        dao.observeAll(includeArchived).map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(includeArchived: Boolean): List<PaymentMethod> = dao.getAll(includeArchived).map { it.toDomain() }

    override suspend fun get(id: Long): PaymentMethod? = dao.get(id)?.toDomain()

    override suspend fun upsert(method: PaymentMethod): Long {
        val rowId = dao.upsert(method.toEntity())
        return if (method.id != 0L) method.id else rowId
    }

    override suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    override suspend fun usage(id: Long) = PaymentMethodUsage(
        expenses = dao.countExpenses(id),
        recurring = dao.countRecurring(id),
    )

    override suspend fun delete(id: Long, moveTo: Long?) = dao.deleteMovingReferences(id, moveTo)

    override suspend fun nextSortOrder(): Int = dao.nextSortOrder()
}

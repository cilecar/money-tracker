package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.IncomeCategoryDao
import app.moneytracker.data.db.dao.IncomeDao
import app.moneytracker.data.db.dao.RefundDao
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

private fun LocalDate?.dayOr(default: Long) = this?.toEpochDay() ?: default

class IncomeRepositoryImpl @Inject constructor(private val dao: IncomeDao) : IncomeRepository {
    override fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Income>> =
        dao.observeBetween(from.dayOr(Long.MIN_VALUE), to.dayOr(Long.MAX_VALUE)).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long) = dao.get(id)?.toDomain()

    override suspend fun insert(income: Income): Long = dao.insert(income.toEntity())

    override suspend fun update(income: Income) = dao.update(income.toEntity())

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun getWithoutRates() = dao.getWithoutRates().map { it.toDomain() }

    override suspend fun fillRates(income: Income): Boolean {
        val rates = requireNotNull(income.rates) { "Nothing to fill" }
        val converted = requireNotNull(income.converted)
        return dao.fillRates(
            income.id, income.amount.minor, income.amount.currency.name, rates.rsdPerEur, rates.rubPerEur, rates.date,
            converted.rsdMinor, converted.eurMinor, converted.rubMinor, income.updatedAt,
        ) == 1
    }
}

class RefundRepositoryImpl @Inject constructor(private val dao: RefundDao) : RefundRepository {
    override fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Refund>> =
        dao.observeBetween(from.dayOr(Long.MIN_VALUE), to.dayOr(Long.MAX_VALUE)).map { list -> list.map { it.toDomain() } }

    override fun observeForExpense(expenseId: Long): Flow<List<Refund>> =
        dao.observeForExpense(expenseId).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long) = dao.get(id)?.toDomain()

    override suspend fun insert(refund: Refund): Long = dao.insert(refund.toEntity())

    override suspend fun update(refund: Refund) = dao.update(refund.toEntity())

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun getWithoutRates() = dao.getWithoutRates().map { it.toDomain() }

    override suspend fun fillRates(refund: Refund): Boolean {
        val rates = requireNotNull(refund.rates) { "Nothing to fill" }
        val converted = requireNotNull(refund.converted)
        return dao.fillRates(
            refund.id, refund.amount.minor, refund.amount.currency.name, rates.rsdPerEur, rates.rubPerEur, rates.date,
            converted.rsdMinor, converted.eurMinor, converted.rubMinor, refund.updatedAt,
        ) == 1
    }
}

class IncomeCategoryRepositoryImpl @Inject constructor(private val dao: IncomeCategoryDao) : IncomeCategoryRepository {
    override fun observeAll(includeArchived: Boolean): Flow<List<IncomeCategory>> =
        dao.observeAll(includeArchived).map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(includeArchived: Boolean) = dao.getAll(includeArchived).map { it.toDomain() }

    override suspend fun upsert(category: IncomeCategory): Long = dao.upsert(category.toEntity())

    override suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    override suspend fun countIncomes(id: Long) = dao.countIncomes(id)

    override suspend fun delete(id: Long, moveTo: Long?) = dao.deleteMovingIncomes(id, moveTo)

    override suspend fun nextSortOrder() = dao.nextSortOrder()
}

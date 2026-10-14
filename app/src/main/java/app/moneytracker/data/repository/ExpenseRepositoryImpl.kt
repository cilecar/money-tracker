package app.moneytracker.data.repository

import app.moneytracker.data.db.ExpenseQueries
import app.moneytracker.data.db.dao.ExpenseDao
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val dao: ExpenseDao,
) : ExpenseRepository {

    override fun observe(id: Long): Flow<Expense?> = dao.observe(id).map { it?.toDomain() }

    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<Expense>> =
        dao.observeBetween(from, to).map { list -> list.map { it.toDomain() } }

    override fun observe(filter: ExpenseFilter): Flow<List<Expense>> =
        dao.observeRaw(ExpenseQueries.build(filter)).map { list ->
            list.asSequence()
                .filter { ExpenseQueries.matchesQuery(it.expense.comment, it.tags.map { t -> t.name }, filter.query) }
                .map { it.toDomain() }
                .toList()
        }

    override fun observeRecentCategoryIds(limit: Int): Flow<List<Long>> = dao.observeRecentCategoryIds(limit)

    override fun observeTags(): Flow<List<String>> = dao.observeTags()

    override suspend fun get(id: Long): Expense? = dao.get(id)?.toDomain()

    override suspend fun getWithoutRates(): List<Expense> = dao.getWithoutRates().map { it.toDomain() }

    override suspend fun insert(expense: Expense): Long =
        dao.insertWithTags(expense.toEntity(), Expense.normalizeTags(expense.tags))

    override suspend fun update(expense: Expense) {
        require(expense.id != 0L) { "Cannot update unsaved expense" }
        dao.updateWithTags(expense.toEntity(), Expense.normalizeTags(expense.tags))
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

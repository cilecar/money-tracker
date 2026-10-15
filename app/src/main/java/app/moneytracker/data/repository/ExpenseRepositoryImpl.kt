package app.moneytracker.data.repository

import androidx.room.withTransaction
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.ExpenseQueries
import app.moneytracker.data.db.dao.ExpenseDao
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.model.usableFor
import app.moneytracker.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
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

    /**
     * Копия без курса могла быть прочитана до того, как FillPendingRates записал курс. Тогда берём
     * курс из базы и пересчитываем по нему (сумма могла измениться), а не затираем его NULL-ами.
     * Чтение и запись — в одной транзакции, чтобы заполнение не вклинилось между ними.
     */
    override suspend fun update(expense: Expense) {
        require(expense.id != 0L) { "Cannot update unsaved expense" }
        db.withTransaction {
            // Снапшот без курса USDT не годится для суммы в USDT — тогда расход по-прежнему ждёт курса.
            val stored = if (expense.rates == null) dao.get(expense.id)?.toDomain()?.rates.usableFor(expense.amount.currency) else null
            val toWrite = stored?.let { expense.copy(rates = it, converted = it.convertAll(expense.amount)) } ?: expense
            dao.updateWithTags(toWrite.toEntity(), Expense.normalizeTags(toWrite.tags))
        }
    }

    override suspend fun fillRates(expense: Expense): Boolean {
        val rates = requireNotNull(expense.rates) { "Nothing to fill" }
        val converted = requireNotNull(expense.converted)
        return dao.fillRates(
            id = expense.id,
            amountMinor = expense.amount.minor,
            currency = expense.amount.currency.name,
            rsdPerEur = rates.rsdPerEur,
            rubPerEur = rates.rubPerEur,
            usdtPerEur = rates.usdtPerEur,
            rateDate = rates.date,
            rsdMinor = converted.rsdMinor,
            eurMinor = converted.eurMinor,
            rubMinor = converted.rubMinor,
            updatedAt = expense.updatedAt,
        ) == 1
    }

    override suspend fun delete(id: Long) = dao.delete(id)
}

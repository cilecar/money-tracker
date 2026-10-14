package app.moneytracker.data.repository

import androidx.room.withTransaction
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.dao.RecurringDao
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.RecurringRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class RecurringRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: RecurringDao,
) : RecurringRepository {

    override fun observeAll(): Flow<List<RecurringExpense>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long): RecurringExpense? = dao.get(id)?.toDomain()

    override suspend fun getDue(today: LocalDate): List<RecurringExpense> = dao.getDue(today).map { it.toDomain() }

    override suspend fun upsert(recurring: RecurringExpense): Long {
        val rowId = dao.upsert(recurring.toEntity())
        return if (recurring.id != 0L) recurring.id else rowId
    }

    override suspend fun delete(id: Long) = dao.delete(id)

    /** Транзакция охватывает два DAO, поэтому она здесь, через withTransaction, а не в одном DAO. */
    override suspend fun recordOccurrence(expense: Expense, recurringId: Long, nextRunDate: LocalDate, active: Boolean) {
        db.withTransaction {
            db.expenseDao().insertWithTags(expense.toEntity(), Expense.normalizeTags(expense.tags))
            dao.advance(recurringId, nextRunDate, active)
        }
    }

    private fun RecurringExpenseEntity.toDomain() = RecurringExpense(
        id = id,
        amount = Money(amountMinor, currency),
        categoryId = categoryId,
        paymentMethodId = paymentMethodId,
        comment = comment,
        tags = tags,
        frequency = frequency,
        interval = interval,
        dayOfMonth = dayOfMonth,
        startDate = startDate,
        endDate = endDate,
        nextRunDate = nextRunDate,
        active = active,
    )

    private fun RecurringExpense.toEntity() = RecurringExpenseEntity(
        id = id,
        amountMinor = amount.minor,
        currency = amount.currency,
        categoryId = categoryId,
        paymentMethodId = paymentMethodId,
        comment = comment,
        tags = tags,
        frequency = frequency,
        interval = interval,
        dayOfMonth = dayOfMonth,
        startDate = startDate,
        endDate = endDate,
        nextRunDate = nextRunDate,
        active = active,
    )
}

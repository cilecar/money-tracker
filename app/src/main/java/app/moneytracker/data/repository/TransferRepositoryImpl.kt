package app.moneytracker.data.repository

import androidx.room.withTransaction
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Transfer
import app.moneytracker.domain.repository.TransferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

class TransferRepositoryImpl @Inject constructor(private val db: AppDatabase) : TransferRepository {
    private val dao = db.transferDao()
    private val expenses = db.expenseDao()

    override fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Transfer>> =
        dao.observeBetween(from?.toEpochDay() ?: Long.MIN_VALUE, to?.toEpochDay() ?: Long.MAX_VALUE).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long): Transfer? = dao.get(id)?.toDomain()

    /** Перевод без комиссии или комиссия без перевода не должны остаться даже при сбое — всё в одной транзакции. */
    override suspend fun save(transfer: Transfer, fee: Expense?): Long = db.withTransaction {
        val oldFeeId = dao.get(transfer.id)?.feeExpenseId
        val feeId = when {
            fee == null -> null
            fee.id != 0L && expenses.get(fee.id) != null -> fee.id.also { expenses.updateWithTags(fee.toEntity(), fee.tags) }
            else -> expenses.insertWithTags(fee.toEntity(), fee.tags)
        }
        val rowId = dao.upsert(transfer.copy(feeExpenseId = feeId).toEntity())
        // Комиссию убрали — удаляем её расход (после upsert: ссылка на него уже снята).
        if (oldFeeId != null && oldFeeId != feeId) expenses.delete(oldFeeId)
        if (transfer.id != 0L) transfer.id else rowId
    }

    override suspend fun delete(id: Long) = db.withTransaction {
        val feeId = dao.get(id)?.feeExpenseId
        dao.delete(id)
        feeId?.let { expenses.delete(it) }
        Unit
    }
}

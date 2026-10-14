package app.moneytracker.domain.repository

import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.PaymentMethodUsage
import kotlinx.coroutines.flow.Flow

interface PaymentMethodRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<PaymentMethod>>

    suspend fun getAll(includeArchived: Boolean = true): List<PaymentMethod>

    suspend fun get(id: Long): PaymentMethod?

    suspend fun upsert(method: PaymentMethod): Long

    suspend fun setArchived(id: Long, archived: Boolean)

    suspend fun usage(id: Long): PaymentMethodUsage

    /** Переносит расходы и регулярные платежи в [moveTo] (если задан) и удаляет способ оплаты. */
    suspend fun delete(id: Long, moveTo: Long?)

    suspend fun nextSortOrder(): Int
}

package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountBalance
import app.moneytracker.domain.model.AccountUsage
import app.moneytracker.domain.model.BalanceAdjustment
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Account>>

    /** Остатки всех счетов, включая архивные (их может отфильтровать UI). */
    fun observeBalances(): Flow<List<AccountBalance>>

    suspend fun getAll(includeArchived: Boolean = true): List<Account>

    suspend fun get(id: Long): Account?

    suspend fun upsert(account: Account): Long

    suspend fun setArchived(id: Long, archived: Boolean)

    suspend fun usage(id: Long): AccountUsage

    /** Есть ли операции по счёту (тогда нельзя менять его валюту). */
    suspend fun hasOperations(id: Long): Boolean

    /** Переносит расходы и регулярные платежи в [moveTo] (если задан), удаляет корректировки и сам счёт. */
    suspend fun delete(id: Long, moveTo: Long?)

    suspend fun nextSortOrder(): Int

    suspend fun addAdjustment(adjustment: BalanceAdjustment): Long

    fun observeAdjustments(accountId: Long): Flow<List<BalanceAdjustment>>

    suspend fun deleteAdjustment(id: Long)
}

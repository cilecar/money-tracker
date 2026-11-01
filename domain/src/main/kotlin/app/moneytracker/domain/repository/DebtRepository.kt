package app.moneytracker.domain.repository

import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.ExpenseShare
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.Settlement
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface PersonRepository {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Person>>

    suspend fun get(id: Long): Person?

    suspend fun upsert(person: Person): Long

    suspend fun setArchived(id: Long, archived: Boolean)

    suspend fun nextSortOrder(): Int
}

/**
 * Доли «мне должны» и записи «за меня заплатили». Доли сохраняются вместе с расходом —
 * см. [ExpenseRepository.save]; здесь — чтение и погашение.
 */
interface DebtRepository {
    suspend fun getSharesForExpense(expenseId: Long): List<ExpenseShare>

    fun observeSharesForExpense(expenseId: Long): Flow<List<ExpenseShare>>

    /** Доли расходов с localDate **расхода** в [from, to] (null — без границы): доля относится к дню покупки. */
    fun observeSharesBetween(from: LocalDate?, to: LocalDate?): Flow<List<ExpenseShare>>

    /** «За меня заплатили» с localDate в [from, to], новые сверху. */
    fun observePaidForMeBetween(from: LocalDate?, to: LocalDate?): Flow<List<PaidForMe>>

    suspend fun getPaidForMe(id: Long): PaidForMe?

    /** Если id != 0, запись восстанавливается с тем же id (undo). */
    suspend fun insertPaidForMe(paidForMe: PaidForMe): Long

    /** Погашение не меняется: оно правится только через [settle] / [unsettle]. */
    suspend fun updatePaidForMe(paidForMe: PaidForMe)

    suspend fun deletePaidForMe(id: Long)

    suspend fun getPaidForMeWithoutRates(): List<PaidForMe>

    /** Как [RefundRepository.fillRates]: пишет только курсы и только если запись не менялась. */
    suspend fun fillPaidForMeRates(paidForMe: PaidForMe): Boolean

    /** Долги обоих направлений, открытые и погашенные; [personId] null — всех людей. */
    fun observeDebts(personId: Long? = null): Flow<List<Debt>>

    /** Погашенные долги с датой погашения в [from, to] — для строк погашений в ленте. */
    fun observeSettledBetween(from: LocalDate?, to: LocalDate?): Flow<List<Debt>>

    suspend fun getDebt(ref: DebtRef): Debt?

    /** @return false, если долг уже погашен или удалён — тогда ничего не записано. */
    suspend fun settle(ref: DebtRef, settlement: Settlement): Boolean

    suspend fun unsettle(ref: DebtRef)
}

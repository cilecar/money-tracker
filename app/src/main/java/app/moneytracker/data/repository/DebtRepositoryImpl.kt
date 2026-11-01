package app.moneytracker.data.repository

import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.dao.DebtDao
import app.moneytracker.data.db.dao.PersonDao
import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.ExpenseShare
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.Settlement
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.PersonRepository
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class PersonRepositoryImpl @Inject constructor(private val dao: PersonDao) : PersonRepository {
    override fun observeAll(includeArchived: Boolean): Flow<List<Person>> = TODO("Task 5")
    override suspend fun get(id: Long): Person? = TODO("Task 5")
    override suspend fun upsert(person: Person): Long = TODO("Task 5")
    override suspend fun setArchived(id: Long, archived: Boolean): Unit = TODO("Task 5")
    override suspend fun nextSortOrder(): Int = TODO("Task 5")
}

class DebtRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: DebtDao,
    private val clock: Clock,
) : DebtRepository {
    override suspend fun getSharesForExpense(expenseId: Long): List<ExpenseShare> = TODO("Task 5")
    override fun observeSharesForExpense(expenseId: Long): Flow<List<ExpenseShare>> = TODO("Task 5")
    override fun observeSharesBetween(from: LocalDate?, to: LocalDate?): Flow<List<ExpenseShare>> = TODO("Task 5")
    override fun observePaidForMeBetween(from: LocalDate?, to: LocalDate?): Flow<List<PaidForMe>> = TODO("Task 5")
    override suspend fun getPaidForMe(id: Long): PaidForMe? = TODO("Task 5")
    override suspend fun insertPaidForMe(paidForMe: PaidForMe): Long = TODO("Task 5")
    override suspend fun updatePaidForMe(paidForMe: PaidForMe): Unit = TODO("Task 5")
    override suspend fun deletePaidForMe(id: Long): Unit = TODO("Task 5")
    override suspend fun getPaidForMeWithoutRates(): List<PaidForMe> = TODO("Task 5")
    override suspend fun fillPaidForMeRates(paidForMe: PaidForMe): Boolean = TODO("Task 5")
    override fun observeDebts(personId: Long?): Flow<List<Debt>> = TODO("Task 5")
    override fun observeSettledBetween(from: LocalDate?, to: LocalDate?): Flow<List<Debt>> = TODO("Task 5")
    override suspend fun getDebt(ref: DebtRef): Debt? = TODO("Task 5")
    override suspend fun settle(ref: DebtRef, settlement: Settlement): Boolean = TODO("Task 5")
    override suspend fun unsettle(ref: DebtRef): Unit = TODO("Task 5")
}

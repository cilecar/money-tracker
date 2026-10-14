package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.BudgetDao
import app.moneytracker.data.db.entity.BudgetAlertEntity
import app.moneytracker.data.db.entity.BudgetEntity
import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.BudgetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject

class BudgetRepositoryImpl @Inject constructor(private val dao: BudgetDao) : BudgetRepository {

    override fun observeAll(): Flow<List<Budget>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(): List<Budget> = dao.getAll().map { it.toDomain() }

    override suspend fun upsert(budget: Budget): Long =
        dao.save(BudgetEntity(budget.id, budget.categoryId, budget.limit.minor, budget.limit.currency))

    override suspend fun delete(id: Long) = dao.delete(id)

    override suspend fun markAlerted(budgetId: Long, month: YearMonth, level: BudgetLevel): Boolean =
        dao.insertAlert(BudgetAlertEntity(budgetId, month.toString(), level.percent)) != -1L

    private fun BudgetEntity.toDomain() = Budget(id, categoryId, Money(limitMinor, currency))
}

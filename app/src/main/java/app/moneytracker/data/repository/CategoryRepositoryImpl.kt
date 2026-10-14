package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.CategoryDao
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CategoryUsage
import app.moneytracker.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val dao: CategoryDao,
) : CategoryRepository {

    override fun observeAll(includeArchived: Boolean): Flow<List<Category>> =
        dao.observeAll(includeArchived).map { list -> list.map { it.toDomain() } }

    override suspend fun getAll(includeArchived: Boolean): List<Category> = dao.getAll(includeArchived).map { it.toDomain() }

    override suspend fun get(id: Long): Category? = dao.get(id)?.toDomain()

    /** @Upsert возвращает -1 при обновлении, поэтому для существующей записи отдаём её id. */
    override suspend fun upsert(category: Category): Long {
        val rowId = dao.upsert(category.toEntity())
        return if (category.id != 0L) category.id else rowId
    }

    override suspend fun setArchived(ids: Collection<Long>, archived: Boolean) = dao.setArchived(ids.toList(), archived)

    override suspend fun usage(id: Long) = CategoryUsage(
        expenses = dao.countExpenses(id),
        recurring = dao.countRecurring(id),
        budgets = dao.countBudgets(id),
        children = dao.countChildren(id),
    )

    override suspend fun delete(id: Long, moveTo: Long?) = dao.deleteMovingReferences(id, moveTo)

    override suspend fun nextSortOrder(parentId: Long?): Int = dao.nextSortOrder(parentId)
}

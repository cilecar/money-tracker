package app.moneytracker.domain.fake

import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CategoryUsage
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountBalance
import app.moneytracker.domain.model.AccountUsage
import app.moneytracker.domain.model.BalanceAdjustment
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class FakeCategoryRepository(initial: List<Category> = emptyList()) : CategoryRepository {
    val items = MutableStateFlow(initial)
    /** Сколько расходов «привязано» к категории: id → count. Перенос при удалении меняет эти счётчики. */
    val expenseCounts = mutableMapOf<Long, Int>()
    val budgets = mutableSetOf<Long>()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeAll(includeArchived: Boolean): Flow<List<Category>> =
        items.map { list -> list.filter { includeArchived || !it.archived } }
    override suspend fun getAll(includeArchived: Boolean) = items.value.filter { includeArchived || !it.archived }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(category: Category): Long {
        val id = if (category.id == 0L) nextId++ else category.id
        items.value = items.value.filter { it.id != id } + category.copy(id = id)
        return id
    }
    override suspend fun setArchived(ids: Collection<Long>, archived: Boolean) {
        items.value = items.value.map { if (it.id in ids) it.copy(archived = archived) else it }
    }
    override suspend fun usage(id: Long) = CategoryUsage(
        expenses = expenseCounts[id] ?: 0,
        budgets = if (id in budgets) 1 else 0,
        children = items.value.count { it.parentId == id },
    )
    override suspend fun delete(id: Long, moveTo: Long?) {
        val moved = expenseCounts.remove(id) ?: 0
        if (moveTo != null) expenseCounts[moveTo] = (expenseCounts[moveTo] ?: 0) + moved
        budgets -= id
        items.value = items.value.filter { it.id != id }
    }
    override suspend fun nextSortOrder(parentId: Long?) =
        (items.value.filter { it.parentId == parentId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
}

class FakeAccountRepository(initial: List<Account> = emptyList()) : AccountRepository {
    val items = MutableStateFlow(initial)
    val expenseCounts = mutableMapOf<Long, Int>()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeAll(includeArchived: Boolean): Flow<List<Account>> =
        items.map { list -> list.filter { includeArchived || !it.archived } }
    override suspend fun getAll(includeArchived: Boolean) = items.value.filter { includeArchived || !it.archived }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun upsert(method: Account): Long {
        val id = if (method.id == 0L) nextId++ else method.id
        items.value = items.value.filter { it.id != id } + method.copy(id = id)
        return id
    }
    override suspend fun setArchived(id: Long, archived: Boolean) {
        items.value = items.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }
    override suspend fun usage(id: Long) = AccountUsage(expenses = expenseCounts[id] ?: 0)
    override suspend fun delete(id: Long, moveTo: Long?) {
        val moved = expenseCounts.remove(id) ?: 0
        if (moveTo != null) expenseCounts[moveTo] = (expenseCounts[moveTo] ?: 0) + moved
        items.value = items.value.filter { it.id != id }
    }
    override suspend fun nextSortOrder() = (items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** Расходы по счёту в его валюте — вместо SQL-запроса баланса. */
    val spent = mutableMapOf<Long, Long>()
    val adjustments = MutableStateFlow<List<BalanceAdjustment>>(emptyList())

    override fun observeBalances(): Flow<List<AccountBalance>> = combine(items, adjustments) { accounts, adj ->
        accounts.map { a ->
            val minor = a.openingBalance.minor - (spent[a.id] ?: 0) + adj.filter { it.accountId == a.id }.sumOf { it.amount.minor }
            AccountBalance(a, Money(minor, a.currency))
        }
    }
    override suspend fun hasOperations(id: Long) = (expenseCounts[id] ?: 0) > 0 || adjustments.value.any { it.accountId == id }
    override suspend fun addAdjustment(adjustment: BalanceAdjustment): Long {
        val id = (adjustments.value.maxOfOrNull { it.id } ?: 0) + 1
        adjustments.value += adjustment.copy(id = id)
        return id
    }
    override fun observeAdjustments(accountId: Long) = adjustments.map { list -> list.filter { it.accountId == accountId } }
    override suspend fun deleteAdjustment(id: Long) {
        adjustments.value = adjustments.value.filter { it.id != id }
    }
}

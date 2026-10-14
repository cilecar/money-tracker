package app.moneytracker.domain.fake

import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.repository.IncomeCategoryRepository
import app.moneytracker.domain.repository.IncomeRepository
import app.moneytracker.domain.repository.RefundRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private fun LocalDate.within(from: LocalDate?, to: LocalDate?) = (from == null || this >= from) && (to == null || this <= to)

class FakeIncomeRepository : IncomeRepository {
    val items = MutableStateFlow<List<Income>>(emptyList())
    private var nextId = 1L

    override fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Income>> =
        items.map { list -> list.filter { it.localDate.within(from, to) }.sortedByDescending { it.occurredAt } }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun insert(income: Income): Long {
        val id = if (income.id == 0L) nextId++ else income.id
        items.value = items.value + income.copy(id = id)
        return id
    }
    override suspend fun update(income: Income) {
        items.value = items.value.map { if (it.id == income.id) income else it }
    }
    override suspend fun delete(id: Long) {
        items.value = items.value.filter { it.id != id }
    }
    override suspend fun getWithoutRates() = items.value.filter { it.rates == null }
    override suspend fun fillRates(income: Income): Boolean {
        val current = get(income.id) ?: return false
        if (current.rates != null || current.amount != income.amount) return false
        update(current.copy(rates = income.rates, converted = income.converted))
        return true
    }
}

class FakeRefundRepository : RefundRepository {
    val items = MutableStateFlow<List<Refund>>(emptyList())
    private var nextId = 1L

    override fun observeBetween(from: LocalDate?, to: LocalDate?): Flow<List<Refund>> =
        items.map { list -> list.filter { it.localDate.within(from, to) }.sortedByDescending { it.occurredAt } }
    override fun observeForExpense(expenseId: Long) = items.map { list -> list.filter { it.expenseId == expenseId } }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun insert(refund: Refund): Long {
        val id = if (refund.id == 0L) nextId++ else refund.id
        items.value = items.value + refund.copy(id = id)
        return id
    }
    override suspend fun update(refund: Refund) {
        items.value = items.value.map { if (it.id == refund.id) refund else it }
    }
    override suspend fun delete(id: Long) {
        items.value = items.value.filter { it.id != id }
    }
    override suspend fun getWithoutRates() = items.value.filter { it.rates == null }
    override suspend fun fillRates(refund: Refund): Boolean {
        val current = get(refund.id) ?: return false
        if (current.rates != null || current.amount != refund.amount) return false
        update(current.copy(rates = refund.rates, converted = refund.converted))
        return true
    }
}

class FakeIncomeCategoryRepository(initial: List<IncomeCategory> = emptyList()) : IncomeCategoryRepository {
    val items = MutableStateFlow(initial)
    val incomeCounts = mutableMapOf<Long, Int>()
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeAll(includeArchived: Boolean) = items.map { list -> list.filter { includeArchived || !it.archived } }
    override suspend fun getAll(includeArchived: Boolean) = items.value.filter { includeArchived || !it.archived }
    override suspend fun upsert(category: IncomeCategory): Long {
        val id = if (category.id == 0L) nextId++ else category.id
        items.value = items.value.filter { it.id != id } + category.copy(id = id)
        return id
    }
    override suspend fun setArchived(id: Long, archived: Boolean) {
        items.value = items.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }
    override suspend fun countIncomes(id: Long) = incomeCounts[id] ?: 0
    override suspend fun delete(id: Long, moveTo: Long?) {
        val moved = incomeCounts.remove(id) ?: 0
        if (moveTo != null) incomeCounts[moveTo] = (incomeCounts[moveTo] ?: 0) + moved
        items.value = items.value.filter { it.id != id }
    }
    override suspend fun nextSortOrder() = (items.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
}

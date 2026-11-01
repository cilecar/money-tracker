package app.moneytracker.domain.fake

import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.ExpenseShare
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.Settlement
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.PersonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private fun LocalDate.within(from: LocalDate?, to: LocalDate?) = (from == null || this >= from) && (to == null || this <= to)

class FakePersonRepository(initial: List<Person> = emptyList()) : PersonRepository {
    val items = MutableStateFlow(initial.associateBy { it.id })
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override fun observeAll(includeArchived: Boolean): Flow<List<Person>> =
        items.map { m -> m.values.filter { includeArchived || !it.archived }.sortedWith(compareBy({ it.sortOrder }, { it.id })) }
    override suspend fun get(id: Long) = items.value[id]
    override suspend fun upsert(person: Person): Long {
        val id = if (person.id == 0L) nextId++ else person.id
        items.value += id to person.copy(id = id)
        return id
    }
    override suspend fun setArchived(id: Long, archived: Boolean) {
        items.value[id]?.let { items.value += id to it.copy(archived = archived) }
    }
    override suspend fun nextSortOrder() = (items.value.values.maxOfOrNull { it.sortOrder } ?: -1) + 1
}

/** Доли читает из [expenses] — там они сохраняются вместе с расходом. */
class FakeDebtRepository(private val expenses: FakeExpenseRepository) : DebtRepository {
    val paidForMe = MutableStateFlow<Map<Long, PaidForMe>>(emptyMap())
    private var nextId = 1L

    override suspend fun getSharesForExpense(expenseId: Long) = expenses.shares.value.values.filter { it.expenseId == expenseId }
    override fun observeSharesForExpense(expenseId: Long) = expenses.shares.map { m -> m.values.filter { it.expenseId == expenseId } }
    override fun observeSharesBetween(from: LocalDate?, to: LocalDate?): Flow<List<ExpenseShare>> =
        combine(expenses.items, expenses.shares) { e, s -> s.values.filter { e[it.expenseId]?.localDate?.within(from, to) == true } }

    override fun observePaidForMeBetween(from: LocalDate?, to: LocalDate?) =
        paidForMe.map { m -> m.values.filter { it.localDate.within(from, to) }.sortedByDescending { it.occurredAt } }
    override suspend fun getPaidForMe(id: Long) = paidForMe.value[id]
    override suspend fun insertPaidForMe(paidForMe: PaidForMe): Long {
        val id = if (paidForMe.id == 0L) nextId++ else paidForMe.id
        this.paidForMe.value += id to paidForMe.copy(id = id)
        return id
    }
    override suspend fun updatePaidForMe(paidForMe: PaidForMe) {
        val stored = this.paidForMe.value[paidForMe.id] ?: return
        this.paidForMe.value += paidForMe.id to paidForMe.copy(settlement = stored.settlement)
    }
    override suspend fun deletePaidForMe(id: Long) { paidForMe.value -= id }
    override suspend fun getPaidForMeWithoutRates() = paidForMe.value.values.filter { it.rates == null }
    override suspend fun fillPaidForMeRates(paidForMe: PaidForMe): Boolean {
        val stored = this.paidForMe.value[paidForMe.id]?.takeIf { it.rates == null && it.amount == paidForMe.amount } ?: return false
        this.paidForMe.value += paidForMe.id to stored.copy(rates = paidForMe.rates, converted = paidForMe.converted, updatedAt = paidForMe.updatedAt)
        return true
    }

    private fun debts(e: Map<Long, app.moneytracker.domain.model.Expense>, s: Map<Long, ExpenseShare>, p: Map<Long, PaidForMe>): List<Debt> =
        s.values.mapNotNull { share -> e[share.expenseId]?.let { Debt.of(share, it) } } + p.values.map { Debt.of(it) }

    private val all = combine(expenses.items, expenses.shares, paidForMe, ::debts)

    override fun observeDebts(personId: Long?) = all.map { list -> list.filter { personId == null || it.personId == personId } }
    override fun observeSettledBetween(from: LocalDate?, to: LocalDate?) =
        all.map { list -> list.filter { it.settlement?.localDate?.within(from, to) == true } }
    override suspend fun getDebt(ref: DebtRef) = debts(expenses.items.value, expenses.shares.value, paidForMe.value).firstOrNull { it.ref == ref }

    override suspend fun settle(ref: DebtRef, settlement: Settlement): Boolean = setSettlement(ref, settlement, requireOpen = true)
    override suspend fun unsettle(ref: DebtRef) { setSettlement(ref, null, requireOpen = false) }

    private fun setSettlement(ref: DebtRef, settlement: Settlement?, requireOpen: Boolean): Boolean = when (ref.direction) {
        DebtDirection.OWED_TO_ME -> {
            val share = expenses.shares.value[ref.id]
            if (share == null || (requireOpen && share.isSettled)) false
            else { expenses.shares.value += ref.id to share.copy(settlement = settlement); true }
        }
        DebtDirection.I_OWE -> {
            val p = paidForMe.value[ref.id]
            if (p == null || (requireOpen && p.settlement != null)) false
            else { paidForMe.value += ref.id to p.copy(settlement = settlement); true }
        }
    }
}

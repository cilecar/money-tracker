package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.Settlement
import app.moneytracker.domain.model.cleanComment
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.PersonRepository
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class PaidForMeInput(
    /** 0 — новая запись. */
    val id: Long = 0,
    val amount: Money,
    val personId: Long,
    val categoryId: Long,
    val occurredAt: Instant,
    val comment: String? = null,
)

/** «За меня заплатили». Снапшот курса — как у расходов: фиксируется при создании, при правках не меняется. */
class SavePaidForMeUseCase @Inject constructor(
    private val debts: DebtRepository,
    private val rates: ExchangeRateRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: PaidForMeInput): Long = save(input).also { checkBudgets() }

    private suspend fun save(input: PaidForMeInput): Long {
        require(input.amount.minor > 0) { "Amount must be positive" }
        val now = clock.instant()
        if (input.id == 0L) {
            return debts.insertPaidForMe(
                PaidForMe.create(input.amount, input.personId, input.categoryId, input.occurredAt, clock.zone, rates.current()?.snapshot, now, input.comment),
            )
        }
        val old = requireNotNull(debts.getPaidForMe(input.id)) { "PaidForMe ${input.id} not found" }
        // Погашено: деньги уже ушли этому человеку именно столько — менять кому и сколько нельзя.
        if (old.settlement != null) {
            check(input.personId == old.personId && input.amount == old.amount) { "Settled PaidForMe ${old.id} keeps person and amount" }
        }
        var updated = old
        if (input.occurredAt != old.occurredAt) updated = updated.withOccurredAt(input.occurredAt, clock.zone, now)
        if (input.amount != old.amount) updated = updated.withAmount(input.amount, now)
        debts.updatePaidForMe(
            updated.copy(personId = input.personId, categoryId = input.categoryId, comment = cleanComment(input.comment), updatedAt = now),
        )
        return old.id
    }
}

/**
 * Погашение долга целиком. [amount] — сколько реально пришло на счёт или ушло с него, в валюте счёта.
 * @return false, если долг уже погашен или удалён.
 */
class SettleDebtUseCase @Inject constructor(
    private val debts: DebtRepository,
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(ref: DebtRef, accountId: Long, amount: Money, occurredAt: Instant): Boolean {
        val account = requireNotNull(accounts.get(accountId)) { "Account $accountId not found" }
        require(amount.currency == account.currency) { "Settlement must be in account currency ${account.currency}" }
        return debts.settle(ref, Settlement.create(accountId, amount, occurredAt, clock.zone))
    }
}

class UnsettleDebtUseCase @Inject constructor(private val debts: DebtRepository) {
    suspend operator fun invoke(ref: DebtRef) = debts.unsettle(ref)
}

/** Новый человек ([id] 0) или переименование. */
class SavePersonUseCase @Inject constructor(private val persons: PersonRepository) {
    suspend operator fun invoke(id: Long, name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Person name must not be blank" }
        if (id == 0L) return persons.upsert(Person(name = clean, sortOrder = persons.nextSortOrder()))
        val old = requireNotNull(persons.get(id)) { "Person $id not found" }
        return persons.upsert(old.copy(name = clean))
    }
}

sealed interface DeletePaidForMeResult {
    data class Deleted(val paidForMe: PaidForMe) : DeletePaidForMeResult
    /** Погашена: удаление молча вернуло бы деньги на счёт — сначала отменить погашение. */
    data object Settled : DeletePaidForMeResult
    data object NotFound : DeletePaidForMeResult
}

class DeletePaidForMeUseCase @Inject constructor(
    private val debts: DebtRepository,
    private val checkBudgets: CheckBudgetsUseCase,
) {
    suspend operator fun invoke(id: Long): DeletePaidForMeResult {
        val p = debts.getPaidForMe(id) ?: return DeletePaidForMeResult.NotFound
        if (p.settlement != null) return DeletePaidForMeResult.Settled
        debts.deletePaidForMe(id)
        checkBudgets()
        return DeletePaidForMeResult.Deleted(p)
    }
}

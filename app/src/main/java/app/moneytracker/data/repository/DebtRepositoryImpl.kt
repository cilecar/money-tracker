package app.moneytracker.data.repository

import androidx.room.withTransaction
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.dao.DebtDao
import app.moneytracker.data.db.dao.PersonDao
import app.moneytracker.data.db.toDebt
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.db.toEntity
import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.ExpenseShare
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.Settlement
import app.moneytracker.domain.model.usableFor
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.PersonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

private fun LocalDate?.dayOr(default: Long) = this?.toEpochDay() ?: default

class PersonRepositoryImpl @Inject constructor(private val dao: PersonDao) : PersonRepository {
    override fun observeAll(includeArchived: Boolean): Flow<List<Person>> =
        dao.observeAll(includeArchived).map { list -> list.map { it.toDomain() } }

    override suspend fun get(id: Long) = dao.get(id)?.toDomain()

    override suspend fun upsert(person: Person): Long {
        val id = dao.upsert(person.toEntity())
        // @Upsert при обновлении возвращает -1.
        return if (person.id != 0L) person.id else id
    }

    override suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    override suspend fun nextSortOrder() = dao.nextSortOrder()
}

class DebtRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: DebtDao,
    private val clock: Clock,
) : DebtRepository {
    override suspend fun getSharesForExpense(expenseId: Long): List<ExpenseShare> =
        dao.sharesForExpense(expenseId).map { it.toDomain() }

    override fun observeSharesForExpense(expenseId: Long): Flow<List<ExpenseShare>> =
        dao.observeSharesForExpense(expenseId).map { list -> list.map { it.toDomain() } }

    override fun observeSharesBetween(from: LocalDate?, to: LocalDate?): Flow<List<ExpenseShare>> =
        dao.observeSharesBetween(from.dayOr(Long.MIN_VALUE), to.dayOr(Long.MAX_VALUE)).map { list -> list.map { it.toDomain() } }

    override fun observePaidForMeBetween(from: LocalDate?, to: LocalDate?): Flow<List<PaidForMe>> =
        dao.observePaidForMeBetween(from.dayOr(Long.MIN_VALUE), to.dayOr(Long.MAX_VALUE)).map { list -> list.map { it.toDomain() } }

    override suspend fun getPaidForMe(id: Long) = dao.getPaidForMe(id)?.toDomain()

    override suspend fun insertPaidForMe(paidForMe: PaidForMe): Long = dao.insertPaidForMe(paidForMe.toEntity())

    /**
     * Погашение берём из базы: его меняют только [settle] / [unsettle]. Копия без курса не затирает
     * курс, записанный после чтения, — как [IncomeRepositoryImpl.update].
     */
    override suspend fun updatePaidForMe(paidForMe: PaidForMe) {
        db.withTransaction {
            val stored = dao.getPaidForMe(paidForMe.id)?.toDomain() ?: return@withTransaction
            var toWrite = paidForMe.copy(settlement = stored.settlement)
            if (toWrite.rates == null) {
                stored.rates.usableFor(toWrite.amount.currency)?.let { toWrite = toWrite.copy(rates = it, converted = it.convertAll(toWrite.amount)) }
            }
            dao.updatePaidForMe(toWrite.toEntity())
        }
    }

    override suspend fun deletePaidForMe(id: Long) = dao.deletePaidForMe(id)

    override suspend fun getPaidForMeWithoutRates() = dao.getPaidForMeWithoutRates().map { it.toDomain() }

    override suspend fun fillPaidForMeRates(paidForMe: PaidForMe): Boolean {
        val rates = requireNotNull(paidForMe.rates) { "Nothing to fill" }
        val converted = requireNotNull(paidForMe.converted)
        return dao.fillPaidForMeRates(
            paidForMe.id, paidForMe.amount.minor, paidForMe.amount.currency.name, rates.rsdPerEur, rates.rubPerEur, rates.usdtPerEur, rates.date,
            converted.rsdMinor, converted.eurMinor, converted.rubMinor, paidForMe.updatedAt,
        ) == 1
    }

    override fun observeDebts(personId: Long?): Flow<List<Debt>> = combine(
        dao.observeShareDebts(personId ?: 0),
        dao.observePaidForMeDebts(personId ?: 0),
    ) { shares, paid -> shares.map { it.toDebt() } + paid.map { Debt.of(it.toDomain()) } }

    override fun observeSettledBetween(from: LocalDate?, to: LocalDate?): Flow<List<Debt>> {
        val f = from.dayOr(Long.MIN_VALUE)
        val t = to.dayOr(Long.MAX_VALUE)
        return combine(dao.observeSettledShares(f, t), dao.observeSettledPaidForMe(f, t)) { shares, paid ->
            shares.map { it.toDebt() } + paid.map { Debt.of(it.toDomain()) }
        }
    }

    override suspend fun getDebt(ref: DebtRef): Debt? = when (ref.direction) {
        DebtDirection.OWED_TO_ME -> dao.shareRow(ref.id)?.toDebt()
        DebtDirection.I_OWE -> dao.getPaidForMe(ref.id)?.toDomain()?.let(Debt::of)
    }

    override suspend fun settle(ref: DebtRef, settlement: Settlement): Boolean {
        val s = settlement
        val now = clock.instant()
        return when (ref.direction) {
            DebtDirection.OWED_TO_ME -> dao.settleShare(ref.id, s.occurredAt, s.localDate, s.accountId, s.amount.minor, s.amount.currency, now)
            DebtDirection.I_OWE -> dao.settlePaidForMe(ref.id, s.occurredAt, s.localDate, s.accountId, s.amount.minor, s.amount.currency, now)
        } == 1
    }

    override suspend fun unsettle(ref: DebtRef) = when (ref.direction) {
        DebtDirection.OWED_TO_ME -> dao.unsettleShare(ref.id, clock.instant())
        DebtDirection.I_OWE -> dao.unsettlePaidForMe(ref.id, clock.instant())
    }
}

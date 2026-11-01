package app.moneytracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseShare
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.model.Settlement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Доли, «за меня заплатили» и погашения на настоящей базе: каскад, транзакции, JOIN с расходом. */
@RunWith(RobolectricTestRunner::class)
class DebtRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var debts: DebtRepositoryImpl
    private lateinit var persons: PersonRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl

    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val rates = RateSnapshot(BigDecimal("117"), BigDecimal("95"), LocalDate.of(2026, 10, 1))
    private var petya = 0L
    private var vasya = 0L
    private var food = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
        debts = DebtRepositoryImpl(db, db.debtDao(), clock)
        persons = PersonRepositoryImpl(db.personDao())
        categories = CategoryRepositoryImpl(db.categoryDao())
        petya = persons.upsert(Person(name = "Петя"))
        vasya = persons.upsert(Person(name = "Вася", sortOrder = 1))
        food = categories.getAll().first().id
    }

    @After
    fun tearDown() = db.close()

    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

    private fun dinner(at: String = "2026-10-10T18:00:00Z") =
        Expense.create(rsd(300000), food, 1, Instant.parse(at), zone, rates, now, comment = "Ужин")

    private fun share(person: Long, amount: Long = 100000) =
        ExpenseShare(expenseId = 0, personId = person, amount = rsd(amount), createdAt = now, updatedAt = now)

    private fun settlement(amount: Long = 100000) = Settlement.create(1, rsd(amount), now, zone)

    @Test
    fun saveInsertsExpenseWithSharesInExpenseCurrency() = runTest {
        val id = expenses.save(dinner(), listOf(share(petya), share(vasya)))
        assertEquals(rsd(300000), expenses.get(id)!!.amount)
        val shares = debts.getSharesForExpense(id)
        assertEquals(listOf(petya, vasya), shares.map { it.personId })
        assertTrue(shares.all { it.expenseId == id && it.amount == rsd(100000) })
    }

    @Test
    fun saveReplacesUnsettledSharesAndKeepsSettled() = runTest {
        val id = expenses.save(dinner(), listOf(share(petya), share(vasya)))
        val petyaShare = debts.getSharesForExpense(id).single { it.personId == petya }
        assertTrue(debts.settle(DebtRef(DebtDirection.OWED_TO_ME, petyaShare.id), settlement()))

        val edited = expenses.get(id)!!.copy(comment = "Ужин в пятницу")
        expenses.save(edited, listOf(share(vasya, 50000)))
        val after = debts.getSharesForExpense(id).associateBy { it.personId }
        assertEquals(petyaShare.id, after.getValue(petya).id)
        assertEquals(settlement(), after.getValue(petya).settlement)
        assertEquals(rsd(50000), after.getValue(vasya).amount)
        assertEquals("Ужин в пятницу", expenses.get(id)!!.comment)

        // null — доли не трогаем.
        expenses.save(expenses.get(id)!!.copy(comment = "Ужин"), null)
        assertEquals(2, debts.getSharesForExpense(id).size)
    }

    @Test
    fun deleteForUndoRestoresSharesWithSameIds() = runTest {
        val id = expenses.save(dinner(), listOf(share(petya), share(vasya)))
        val before = debts.getSharesForExpense(id)

        val deleted = expenses.deleteForUndo(id)!!
        assertEquals(emptyList<ExpenseShare>(), debts.getSharesForExpense(id))
        assertEquals(2, deleted.shares.size)

        expenses.restore(deleted)
        assertEquals(before, debts.getSharesForExpense(id))
    }

    /** Удаление унесло бы погашение из баланса счёта — как у «в долг»: сначала отменить погашение. */
    @Test
    fun expenseWithSettledShareIsNotDeleted() = runTest {
        val id = expenses.save(dinner(), listOf(share(petya)))
        val ref = DebtRef(DebtDirection.OWED_TO_ME, debts.getSharesForExpense(id).single().id)
        debts.settle(ref, settlement())

        assertNull(expenses.deleteForUndo(id))
        assertTrue(expenses.get(id) != null)
        assertTrue(debts.getDebt(ref)!!.isSettled)

        debts.unsettle(ref)
        assertTrue(expenses.deleteForUndo(id) != null)
    }

    @Test
    fun sharesBetweenUsesExpenseDate() = runTest {
        val id = expenses.save(dinner("2026-10-10T18:00:00Z"), listOf(share(petya)))
        val day = LocalDate.of(2026, 10, 10)
        assertEquals(listOf(id), debts.observeSharesBetween(day, day).first().map { it.expenseId })
        assertEquals(emptyList<ExpenseShare>(), debts.observeSharesBetween(day.plusDays(1), null).first())
    }

    @Test
    fun settleIsCompareAndSetAndUnsettleClears() = runTest {
        val id = expenses.save(dinner(), listOf(share(petya)))
        val ref = DebtRef(DebtDirection.OWED_TO_ME, debts.getSharesForExpense(id).single().id)
        assertTrue(debts.settle(ref, settlement()))
        assertFalse(debts.settle(ref, settlement(90000)))
        assertEquals(rsd(100000), debts.getDebt(ref)!!.settlement!!.amount)

        debts.unsettle(ref)
        assertNull(debts.getDebt(ref)!!.settlement)
        assertFalse(debts.settle(DebtRef(DebtDirection.OWED_TO_ME, 999), settlement()))
    }

    @Test
    fun paidForMeRoundTripAndSettlementIsKeptOnUpdate() = runTest {
        val p = PaidForMe.create(rsd(40000), petya, food, Instant.parse("2026-10-11T18:00:00Z"), zone, rates, now, "Кино")
        val id = debts.insertPaidForMe(p)
        assertEquals(p.copy(id = id), debts.getPaidForMe(id))

        val ref = DebtRef(DebtDirection.I_OWE, id)
        debts.settle(ref, settlement(40000))
        debts.updatePaidForMe(debts.getPaidForMe(id)!!.copy(comment = "Кино и попкорн", settlement = null))
        assertEquals("Кино и попкорн", debts.getPaidForMe(id)!!.comment)
        assertEquals(settlement(40000), debts.getPaidForMe(id)!!.settlement)

        assertEquals(listOf(id), debts.observePaidForMeBetween(LocalDate.of(2026, 10, 11), null).first().map { it.id })
        debts.deletePaidForMe(id)
        assertNull(debts.getPaidForMe(id))
    }

    @Test
    fun fillPaidForMeRatesOnlyWhenUnchanged() = runTest {
        val id = debts.insertPaidForMe(PaidForMe.create(rsd(40000), petya, food, now, zone, null, now))
        val pending = debts.getPaidForMeWithoutRates().single()
        assertTrue(debts.fillPaidForMeRates(pending.withRates(rates, now)))
        assertEquals(rates, debts.getPaidForMe(id)!!.rates)
        assertFalse(debts.fillPaidForMeRates(pending.withRates(rates, now)))
    }

    @Test
    fun observeDebtsBuildsBothDirections() = runTest {
        val expenseId = expenses.save(dinner(), listOf(share(petya)))
        val pId = debts.insertPaidForMe(PaidForMe.create(rsd(40000), petya, food, now, zone, rates, now, "Кино"))
        debts.insertPaidForMe(PaidForMe.create(rsd(10000), vasya, food, now, zone, rates, now))

        val forPetya = debts.observeDebts(petya).first().associateBy { it.ref.direction }
        val owed = forPetya.getValue(DebtDirection.OWED_TO_ME)
        assertEquals(1L, owed.expenseAccountId)
        assertEquals("Ужин", owed.comment)
        assertEquals(LocalDate.of(2026, 10, 10), owed.localDate)
        assertEquals(rsd(100000), owed.amount)
        val mine = forPetya.getValue(DebtDirection.I_OWE)
        assertEquals(DebtRef(DebtDirection.I_OWE, pId), mine.ref)
        assertNull(mine.expenseAccountId)
        assertEquals(3, debts.observeDebts().first().size)
        assertTrue(debts.getSharesForExpense(expenseId).isNotEmpty())
    }

    @Test
    fun observeSettledBetweenUsesSettlementDate() = runTest {
        val expenseId = expenses.save(dinner(), listOf(share(petya)))
        val ref = DebtRef(DebtDirection.OWED_TO_ME, debts.getSharesForExpense(expenseId).single().id)
        val pRef = DebtRef(DebtDirection.I_OWE, debts.insertPaidForMe(PaidForMe.create(rsd(40000), petya, food, now, zone, rates, now)))
        debts.settle(ref, settlement())
        debts.settle(pRef, settlement(40000))

        val today = LocalDate.of(2026, 10, 14)
        assertEquals(setOf(ref, pRef), debts.observeSettledBetween(today, today).first().map { it.ref }.toSet())
        assertEquals(emptyList<Any>(), debts.observeSettledBetween(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 13)).first())
    }

    @Test
    fun deletingCategoryMovesPaidForMe() = runTest {
        val other = categories.upsert(Category(name = "Кино", iconKey = "movie", color = 0, sortOrder = 99))
        val id = debts.insertPaidForMe(PaidForMe.create(rsd(40000), petya, other, now, zone, rates, now))
        assertEquals(1, categories.usage(other).expenses)

        categories.delete(other, moveTo = food)
        assertEquals(food, debts.getPaidForMe(id)!!.categoryId)
    }

    @Test
    fun personsArchiveAndOrder() = runTest {
        assertEquals(listOf("Петя", "Вася"), persons.observeAll().first().map { it.name })
        persons.setArchived(petya, true)
        assertEquals(listOf("Вася"), persons.observeAll().first().map { it.name })
        assertEquals(2, persons.observeAll(includeArchived = true).first().size)
        assertEquals(2, persons.nextSortOrder())
        assertEquals("Петя", persons.get(petya)!!.name)
    }
}

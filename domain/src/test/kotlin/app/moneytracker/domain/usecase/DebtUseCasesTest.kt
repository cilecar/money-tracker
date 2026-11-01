package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeAccountRepository
import app.moneytracker.domain.fake.FakeDebtRepository
import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakePersonRepository
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.Money
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DebtUseCasesTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val rates = FakeExchangeRateRepository(clock)
    private val expenses = FakeExpenseRepository()
    private val debts = FakeDebtRepository(expenses)
    private val persons = FakePersonRepository()
    private val card = Account(id = 1, name = "Карта", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = LocalDate.of(2026, 1, 1))
    private val accounts = FakeAccountRepository(listOf(card))

    private val budgets = app.moneytracker.domain.fake.FakeBudgetRepository()
    private val notifier = app.moneytracker.domain.fake.RecordingNotifier()
    private val categories = app.moneytracker.domain.fake.FakeCategoryRepository(listOf(app.moneytracker.domain.model.Category(id = 5, name = "Кино", iconKey = "", color = 0)))
    private val checkBudgets = app.moneytracker.domain.fake.checkBudgets(clock, expenses, categories, budgets, notifier, debts = debts)
    private val savePaidForMe = SavePaidForMeUseCase(debts, rates, checkBudgets, clock)
    private val settle = SettleDebtUseCase(debts, accounts, clock)
    private val unsettle = UnsettleDebtUseCase(debts)
    private val savePerson = SavePersonUseCase(persons)

    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)
    private suspend fun cinema() = savePaidForMe(PaidForMeInput(amount = rsd(40000), personId = 1, categoryId = 5, occurredAt = now, comment = " кино "))

    @Test
    fun `paid for me takes current rates and keeps them on edit`() = runTest {
        rates.setManualRate(CurrencyCode.RSD, BigDecimal("117"), LocalDate.of(2026, 10, 14))
        rates.setManualRate(CurrencyCode.RUB, BigDecimal("95"), LocalDate.of(2026, 10, 14))
        val id = cinema()
        val saved = debts.getPaidForMe(id)!!
        assertEquals(BigDecimal("117"), saved.rates?.rsdPerEur)
        assertEquals("кино", saved.comment)

        rates.setManualRate(CurrencyCode.RSD, BigDecimal("200"), LocalDate.of(2026, 10, 15))
        savePaidForMe(PaidForMeInput(id = id, amount = rsd(50000), personId = 2, categoryId = 6, occurredAt = now))
        val edited = debts.getPaidForMe(id)!!
        assertEquals(BigDecimal("117"), edited.rates?.rsdPerEur)
        assertEquals(rsd(50000), edited.amount)
        assertEquals(2L, edited.personId)
        assertNull(edited.comment)
    }

    @Test
    fun `paid for me without rates is saved anyway`() = runTest {
        assertNull(debts.getPaidForMe(cinema())!!.rates)
    }

    @Test
    fun `settle twice returns false`() = runTest {
        val ref = DebtRef(DebtDirection.I_OWE, cinema())
        assertTrue(settle(ref, accountId = 1, amount = rsd(40000), occurredAt = now))
        assertFalse(settle(ref, accountId = 1, amount = rsd(40000), occurredAt = now))
        val s = debts.getDebt(ref)!!.settlement!!
        assertEquals(1L, s.accountId)
        assertEquals(LocalDate.of(2026, 10, 14), s.localDate)
    }

    @Test
    fun `settle in currency other than account is rejected`() = runTest {
        val ref = DebtRef(DebtDirection.I_OWE, cinema())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { settle(ref, accountId = 1, amount = Money(400, CurrencyCode.EUR), occurredAt = now) }
        }
        assertNull(debts.getDebt(ref)!!.settlement)
    }

    @Test
    fun `unsettle clears settlement`() = runTest {
        val ref = DebtRef(DebtDirection.I_OWE, cinema())
        settle(ref, 1, rsd(40000), now)
        unsettle(ref)
        assertNull(debts.getDebt(ref)!!.settlement)
    }

    @Test
    fun `person name is trimmed and blank is rejected`() = runTest {
        val id = savePerson(0, "  Петя ")
        assertEquals("Петя", persons.get(id)!!.name)
        savePerson(id, "Пётр")
        assertEquals("Пётр", persons.get(id)!!.name)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { savePerson(0, "   ") } }
    }

    @Test
    fun `paid for me triggers budget check`() = runTest {
        budgets.upsert(app.moneytracker.domain.model.Budget(categoryId = 5, limit = rsd(40000)))
        cinema()
        assertEquals(1, notifier.sent.size)
    }

    @Test
    fun `analytics counts my share and paid for me`() = runTest {
        val at = Instant.parse("2026-10-12T18:00:00Z")
        expenses.save(
            app.moneytracker.domain.model.Expense.create(rsd(300000), 5, 1, at, zone, null, at),
            listOf(app.moneytracker.domain.model.ExpenseShare(expenseId = 0, personId = 1, amount = rsd(200000), createdAt = at, updatedAt = at)),
        )
        cinema()
        val observe = ObserveAnalyticsUseCase(
            expenses, categories, accounts, app.moneytracker.domain.fake.FakeIncomeRepository(), app.moneytracker.domain.fake.FakeRefundRepository(),
            app.moneytracker.domain.fake.FakeIncomeCategoryRepository(), debts, clock,
        )
        val report = observe(app.moneytracker.domain.model.AnalyticsPeriod.Month(java.time.YearMonth.of(2026, 10)), CurrencyCode.RSD).first()
        assertEquals(rsd(140000), report.total.total)
        assertTrue(report.accounts.any { it.paidByOthers })
    }

    @Test
    fun `observe debts summarizes open debts`() = runTest {
        val petya = savePerson(0, "Петя")
        val id = savePaidForMe(PaidForMeInput(amount = rsd(40000), personId = petya, categoryId = 5, occurredAt = now))
        val summary = ObserveDebtsUseCase(debts, persons, rates)(CurrencyCode.RSD).first()
        assertEquals(rsd(40000), summary.iOwe)
        assertEquals(rsd(-40000), summary.people.single().net)

        settle(DebtRef(DebtDirection.I_OWE, id), 1, rsd(40000), now)
        assertTrue(ObserveDebtsUseCase(debts, persons, rates)(CurrencyCode.RSD).first().people.isEmpty())
    }

    @Test
    fun `settled paid for me cannot be deleted until unsettled`() = runTest {
        val id = cinema()
        val delete = DeletePaidForMeUseCase(debts, checkBudgets)
        settle(DebtRef(DebtDirection.I_OWE, id), 1, rsd(40000), now)
        assertEquals(DeletePaidForMeResult.Settled, delete(id))
        assertTrue(debts.getPaidForMe(id) != null)

        unsettle(DebtRef(DebtDirection.I_OWE, id))
        val deleted = delete(id) as DeletePaidForMeResult.Deleted
        assertEquals(id, deleted.paidForMe.id)
        assertNull(debts.getPaidForMe(id))
        assertEquals(DeletePaidForMeResult.NotFound, delete(id))
    }

    @Test
    fun `settled paid for me keeps person and amount`() = runTest {
        val id = cinema()
        settle(DebtRef(DebtDirection.I_OWE, id), 1, rsd(40000), now)
        val input = PaidForMeInput(id = id, amount = rsd(40000), personId = 1, categoryId = 5, occurredAt = now)
        assertThrows(IllegalStateException::class.java) { runBlocking { savePaidForMe(input.copy(amount = rsd(50000))) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { savePaidForMe(input.copy(personId = 2)) } }
        // Комментарий, категорию и дату менять можно.
        savePaidForMe(input.copy(comment = "кино и попкорн", categoryId = 6))
        assertEquals("кино и попкорн", debts.getPaidForMe(id)!!.comment)
        assertTrue(debts.getPaidForMe(id)!!.settlement != null)
    }
}

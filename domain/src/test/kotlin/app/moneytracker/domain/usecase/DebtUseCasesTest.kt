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

    private val savePaidForMe = SavePaidForMeUseCase(debts, rates, clock)
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
}

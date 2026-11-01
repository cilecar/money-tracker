package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeDebtRepository
import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeSettingsRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Settlement
import app.moneytracker.domain.model.ShareInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SaveExpenseUseCaseTest {

    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val expenses = FakeExpenseRepository()
    private val rates = FakeExchangeRateRepository(clock)
    private val settings = FakeSettingsRepository()
    private val debts = FakeDebtRepository(expenses)
    private val save = SaveExpenseUseCase(expenses, debts, rates, settings, checkBudgets(clock, expenses), clock)

    private fun input(id: Long = 0, amount: Money = Money(100000, CurrencyCode.RSD), at: Instant = now) =
        ExpenseInput(id = id, amount = amount, categoryId = 1, accountId = 1, occurredAt = at, comment = " кофе ", tags = listOf("a", "A"))

    @Test
    fun `new expense takes current rates and remembers currency`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(LocalDate.of(2026, 10, 14), rsd = "117", rub = "100") }
        rates.refresh()

        val id = save(input(amount = Money(1000, CurrencyCode.EUR)))
        val e = expenses.get(id)!!
        assertEquals(BigDecimal("117"), e.rates!!.rsdPerEur)
        assertEquals(117000L, e.converted!!.rsdMinor)
        assertEquals("кофе", e.comment)
        assertEquals(listOf("a"), e.tags)
        assertEquals(CurrencyCode.EUR, settings.settings.value.inputCurrency)
    }

    @Test
    fun `new expense without rates is saved anyway`() = runTest {
        val id = save(input())
        assertNull(expenses.get(id)!!.rates)
    }

    @Test
    fun `editing date and amount keeps original snapshot`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(LocalDate.of(2026, 10, 14), rsd = "117", rub = "100") }
        rates.refresh()
        val id = save(input())

        // Курс изменился после добавления
        rates.nextResult = { FakeExchangeRateRepository.success(LocalDate.of(2026, 10, 15), rsd = "200", rub = "100") }
        rates.refresh()

        val newDate = Instant.parse("2026-10-01T21:30:00Z") // 23:30 по Белграду
        save(input(id = id, amount = Money(234000, CurrencyCode.RSD), at = newDate))

        val e = expenses.get(id)!!
        assertEquals(LocalDate.of(2026, 10, 1), e.localDate)
        assertEquals(BigDecimal("117"), e.rates!!.rsdPerEur)
        assertEquals(2000L, e.converted!!.eurMinor) // 2340 RSD / 117
    }

    @Test
    fun `saving an expense deleted while the editor was open saves it anew`() = runTest {
        val id = save(input())
        expenses.delete(id)
        val again = save(input(id = id, amount = Money(500_00, CurrencyCode.RSD)))
        assertEquals(Money(500_00, CurrencyCode.RSD), expenses.get(again)!!.amount)
        assertEquals(1, expenses.items.value.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero amount is rejected`() = runTest {
        save(input(amount = Money.zero(CurrencyCode.RSD)))
    }

    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)
    private fun dinner(id: Long = 0, amount: Money = rsd(300000), shares: List<ShareInput>? = null) =
        ExpenseInput(id = id, amount = amount, categoryId = 1, accountId = 1, occurredAt = now, shares = shares)
    private val twoFriends = listOf(ShareInput(1, rsd(100000)), ShareInput(2, rsd(100000)))

    @Test
    fun `new expense with shares saves both`() = runTest {
        val id = save(dinner(shares = twoFriends))
        val shares = debts.getSharesForExpense(id)
        assertEquals(listOf(1L to rsd(100000), 2L to rsd(100000)), shares.map { it.personId to it.amount }.sortedBy { it.first })
    }

    @Test
    fun `shares exceeding amount are rejected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { save(dinner(shares = listOf(ShareInput(1, rsd(300001))))) }
        }
        assertTrue(expenses.items.value.isEmpty())
    }

    @Test
    fun `null shares keep existing shares`() = runTest {
        val id = save(dinner(shares = twoFriends))
        save(dinner(id = id).copy(comment = "ужин"))
        assertEquals(2, debts.getSharesForExpense(id).size)
        assertEquals("ужин", expenses.get(id)!!.comment)
    }

    @Test
    fun `lowering amount below existing shares with null shares is rejected`() = runTest {
        val id = save(dinner(shares = twoFriends))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { save(dinner(id = id, amount = rsd(150000))) }
        }
        assertEquals(rsd(300000), expenses.get(id)!!.amount)
    }

    @Test
    fun `changing currency with existing shares and null shares is rejected`() = runTest {
        val id = save(dinner(shares = twoFriends))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { save(dinner(id = id, amount = Money(3000, CurrencyCode.EUR))) }
        }
    }

    @Test
    fun `settled share must stay unchanged`() = runTest {
        val id = save(dinner(shares = twoFriends))
        val petya = debts.getSharesForExpense(id).single { it.personId == 1L }
        debts.settle(DebtRef(DebtDirection.OWED_TO_ME, petya.id), Settlement.create(1, rsd(100000), now, zone))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { save(dinner(id = id, shares = listOf(ShareInput(1, rsd(50000)), ShareInput(2, rsd(100000))))) }
        }
        // Погашенная доля передана как есть: сохраняется, погашение на месте, вторая доля меняется.
        save(dinner(id = id, shares = listOf(ShareInput(1, rsd(100000)), ShareInput(2, rsd(80000)))))
        val after = debts.getSharesForExpense(id).associateBy { it.personId }
        assertEquals(petya.id, after.getValue(1).id)
        assertTrue(after.getValue(1).isSettled)
        assertEquals(rsd(80000), after.getValue(2).amount)
    }
}

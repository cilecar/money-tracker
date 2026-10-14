package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeSettingsRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val save = SaveExpenseUseCase(expenses, rates, settings, checkBudgets(clock, expenses), clock)

    private fun input(id: Long = 0, amount: Money = Money(100000, CurrencyCode.RSD), at: Instant = now) =
        ExpenseInput(id = id, amount = amount, categoryId = 1, paymentMethodId = 1, occurredAt = at, comment = " кофе ", tags = listOf("a", "A"))

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

    @Test(expected = IllegalArgumentException::class)
    fun `zero amount is rejected`() = runTest {
        save(input(amount = Money.zero(CurrencyCode.RSD)))
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RefreshResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class RatesUseCasesTest {

    private var now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val today = LocalDate.of(2026, 10, 14)

    private val expenses = FakeExpenseRepository()
    private val rates = FakeExchangeRateRepository(clock)
    private val fill = FillPendingRatesUseCase(expenses, rates, checkBudgets(clock, expenses), clock)
    private val refresh = RefreshRatesUseCase(rates, fill, clock)
    private val setManual = SetManualRateUseCase(rates, fill, clock)

    private suspend fun addPendingExpense(): Long = expenses.insert(
        Expense.create(Money(1000, CurrencyCode.EUR), 1, 1, now, ZoneOffset.UTC, rates = null, now = now),
    )

    @Test
    fun `refresh fills expenses saved without rates`() = runTest {
        val id = addPendingExpense()
        rates.nextResult = { FakeExchangeRateRepository.success(today) }

        assertTrue(refresh() is RefreshResult.Success)

        val e = expenses.get(id)!!
        assertEquals(BigDecimal("117.15"), e.rates!!.rsdPerEur)
        assertEquals(117150L, e.converted!!.rsdMinor)
    }

    @Test
    fun `failed refresh leaves pending expenses untouched`() = runTest {
        val id = addPendingExpense()
        assertTrue(refresh() is RefreshResult.Failure)
        assertNull(expenses.get(id)!!.rates)
    }

    @Test
    fun `manual rate unblocks pending expenses once both currencies are known`() = runTest {
        val id = addPendingExpense()

        setManual(CurrencyCode.RSD, BigDecimal("117"))
        assertNull("RUB ещё неизвестен — снапшот собрать нельзя", expenses.get(id)!!.rates)

        setManual(CurrencyCode.RUB, BigDecimal("94"))
        assertEquals(117000L, expenses.get(id)!!.converted!!.rsdMinor)
    }

    @Test
    fun `fill does not touch expenses that already have rates`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(today, rsd = "100") }
        refresh()
        val id = expenses.insert(
            Expense.create(Money(1000, CurrencyCode.EUR), 1, 1, now, ZoneOffset.UTC, rates.current()!!.snapshot, now),
        )
        rates.nextResult = { FakeExchangeRateRepository.success(today.plusDays(1), rsd = "200") }
        refresh()
        assertEquals(100000L, expenses.get(id)!!.converted!!.rsdMinor) // история не «поплыла»
    }

    @Test
    fun `ifStale refreshes only when data is old or missing`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(today) }

        assertTrue(refresh.ifStale() is RefreshResult.Success) // курсов нет — грузим
        now += Duration.ofHours(1)
        assertNull(refresh.ifStale()) // свежие — не грузим
        now += Duration.ofHours(12)
        assertTrue(refresh.ifStale() != null) // устарели
        assertEquals(2, rates.refreshCalls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual rate must be positive`() = runTest {
        setManual(CurrencyCode.RSD, BigDecimal.ZERO)
    }
}

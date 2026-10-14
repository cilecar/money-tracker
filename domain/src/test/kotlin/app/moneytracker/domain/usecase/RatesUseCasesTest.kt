package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeIncomeRepository
import app.moneytracker.domain.fake.FakeRefundRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    private val incomes = FakeIncomeRepository()
    private val refunds = FakeRefundRepository()
    private val rates = FakeExchangeRateRepository(clock)
    private val fill = FillPendingRatesUseCase(expenses, incomes, refunds, rates, checkBudgets(clock, expenses), clock)
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

    @Test
    fun `parallel ifStale calls download once`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(today) }
        val gate = CompletableDeferred<Unit>()
        // Загрузка «висит» в сети — тем временем стартует второй вызов (фоновая задача и запуск приложения).
        val slow = object : ExchangeRateRepository by rates {
            override suspend fun refresh(): RefreshResult = gate.await().let { rates.refresh() }
        }
        val refresh = RefreshRatesUseCase(slow, fill, clock)

        launch { refresh.ifStale() }
        launch { refresh.ifStale() }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, rates.refreshCalls)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `manual rate must be positive`() = runTest {
        setManual(CurrencyCode.RSD, BigDecimal.ZERO)
    }

    @Test
    fun `manual rate outside a sane range is rejected`() = runTest {
        assertTrue(SetManualRateUseCase.isPlausible(CurrencyCode.RSD, BigDecimal("117.4")))
        assertTrue(SetManualRateUseCase.isPlausible(CurrencyCode.RUB, BigDecimal("95.43")))
        // Опечатки на порядок: лишняя цифра или запятая не там.
        assertFalse(SetManualRateUseCase.isPlausible(CurrencyCode.RSD, BigDecimal("1174")))
        assertFalse(SetManualRateUseCase.isPlausible(CurrencyCode.RSD, BigDecimal("11.74")))
        assertFalse(SetManualRateUseCase.isPlausible(CurrencyCode.RUB, BigDecimal("9543")))
        assertFalse(SetManualRateUseCase.isPlausible(CurrencyCode.RUB, BigDecimal("0.0001")))
        try {
            setManual(CurrencyCode.RUB, BigDecimal("9543"))
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(rates.current())
    }

    @Test
    fun `fill does not revert an edit made while it was running`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(today) }
        rates.refresh()
        val commented = addPendingExpense()
        val resized = addPendingExpense()
        // Пользователь сохраняет правки между чтением списка и записью курсов.
        val racing = object : ExpenseRepository by expenses {
            override suspend fun getWithoutRates() = expenses.getWithoutRates().also {
                expenses.update(expenses.get(commented)!!.copy(comment = "правка"))
                expenses.update(expenses.get(resized)!!.withAmount(Money(5000, CurrencyCode.EUR), now))
            }
        }
        FillPendingRatesUseCase(racing, FakeIncomeRepository(), FakeRefundRepository(), rates, checkBudgets(clock, expenses), clock)()

        val c = expenses.get(commented)!!
        assertEquals("правка", c.comment)
        assertTrue(c.hasRates)
        // Сумма изменилась после чтения: курсы с пересчётом старой суммы не пишем, заполним в следующий раз.
        val r = expenses.get(resized)!!
        assertEquals(Money(5000, CurrencyCode.EUR), r.amount)
        assertNull(r.converted)
    }

    @Test
    fun `fill skips an expense deleted while it was running`() = runTest {
        rates.nextResult = { FakeExchangeRateRepository.success(today) }
        rates.refresh()
        val id = addPendingExpense()
        val racing = object : ExpenseRepository by expenses {
            override suspend fun getWithoutRates() = expenses.getWithoutRates().also { expenses.delete(id) }
        }
        FillPendingRatesUseCase(racing, FakeIncomeRepository(), FakeRefundRepository(), rates, checkBudgets(clock, expenses), clock)()
        assertNull(expenses.get(id))
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeRecurringRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RecurringExpense
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class RunRecurringUseCaseTest {

    private val zone = ZoneId.of("Europe/Belgrade")
    private val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
    private val expenses = FakeExpenseRepository()
    private val recurring = FakeRecurringRepository(expenses)
    private val rates = FakeExchangeRateRepository(clock)
    private val run = RunRecurringUseCase(recurring, rates, checkBudgets(clock, expenses), clock)

    private suspend fun add(start: String, end: String? = null, frequency: Frequency = Frequency.MONTHLY) = recurring.upsert(
        RecurringExpense.create(
            amount = Money(120000, CurrencyCode.RSD),
            categoryId = 4,
            paymentMethodId = 2,
            frequency = frequency,
            interval = 1,
            startDate = LocalDate.parse(start),
            endDate = end?.let(LocalDate::parse),
            comment = "Аренда",
            tags = listOf("дом"),
        ),
    )

    @Test
    fun `catches up missed months and is idempotent`() = runTest {
        val id = add("2026-08-05")
        assertEquals(3, run())
        assertEquals(0, run())

        val created = expenses.items.value.values.sortedBy { it.localDate }
        assertEquals(listOf("2026-08-05", "2026-09-05", "2026-10-05").map(LocalDate::parse), created.map { it.localDate })
        assertEquals(id, created.first().recurringId)
        assertEquals("Аренда", created.first().comment)
        assertEquals(listOf("дом"), created.first().tags)
        assertEquals(LocalDate.parse("2026-11-05"), recurring.get(id)!!.nextRunDate)
    }

    @Test
    fun `stops at end date and deactivates`() = runTest {
        val id = add("2026-08-05", end = "2026-09-30")
        assertEquals(2, run())
        assertFalse(recurring.get(id)!!.active)
    }

    @Test
    fun `future template creates nothing`() = runTest {
        add("2026-10-15")
        assertEquals(0, run())
    }

    @Test
    fun `uses current rates or saves without them`() = runTest {
        add("2026-10-14")
        run()
        assertNull(expenses.items.value.values.single().rates)

        rates.nextResult = { FakeExchangeRateRepository.success(LocalDate.parse("2026-10-14"), rsd = "117") }
        rates.refresh()
        add("2026-10-01", frequency = Frequency.WEEKLY) // 1, 8 октября
        run()
        val withRates = expenses.items.value.values.filter { it.rates != null }
        assertEquals(2, withRates.size)
        assertEquals(BigDecimal("117"), withRates.first().rates!!.rsdPerEur)
    }

    @Test
    fun `daily catch up is capped`() = runTest {
        add("2020-01-01", frequency = Frequency.DAILY)
        assertEquals(366, run())
    }
}

class SaveRecurringUseCaseTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
    private val expenses = FakeExpenseRepository()
    private val recurring = FakeRecurringRepository(expenses)
    private val run = RunRecurringUseCase(recurring, FakeExchangeRateRepository(clock), checkBudgets(clock, expenses), clock)
    private val save = SaveRecurringUseCase(recurring, run, clock)

    private fun template(start: String, frequency: Frequency = Frequency.MONTHLY) = RecurringExpense.create(
        Money(100, CurrencyCode.EUR), 1, 1, frequency, 1, LocalDate.parse(start),
    )

    @Test
    fun `new template with past start catches up immediately`() = runTest {
        val id = save(template("2026-09-14"))
        assertEquals(2, expenses.items.value.size) // 14 сентября и 14 октября (сегодня)
        assertEquals(LocalDate.parse("2026-11-14"), recurring.get(id)!!.nextRunDate)
    }

    @Test
    fun `editing amount keeps next date, editing schedule does not duplicate`() = runTest {
        val id = save(template("2026-09-14"))
        save(recurring.get(id)!!.copy(amount = Money(200, CurrencyCode.EUR)))
        assertEquals(LocalDate.parse("2026-11-14"), recurring.get(id)!!.nextRunDate)

        // Перешли на еженедельный с той же даты начала: следующая — первая по новому расписанию с сегодня.
        val weekly = RecurringExpense.create(Money(200, CurrencyCode.EUR), 1, 1, Frequency.WEEKLY, 1, LocalDate.parse("2026-09-14")).copy(id = id)
        save(weekly)
        // 14.09 + 4 недели = 12.10, + 5 недель = 19.10 → первая ≥ 14.10 — это 19.10
        assertEquals(LocalDate.parse("2026-10-19"), recurring.get(id)!!.nextRunDate)
        assertEquals(2, expenses.items.value.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `end before start is rejected`() = runTest {
        save(template("2026-10-14").copy(endDate = LocalDate.parse("2026-10-01")))
    }
}

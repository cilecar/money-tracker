package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeDebtRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeIncomeRepository
import app.moneytracker.domain.fake.FakeRefundRepository
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.MonthSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours

/** Часы, которые идут вместе с виртуальным временем теста. */
private class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val start: Instant, private val zone: ZoneId) : Clock() {
    override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
    override fun getZone() = zone
    override fun withZone(zone: ZoneId) = SchedulerClock(scheduler, start, zone)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveMonthSummaryUseCaseTest {
    @Test
    fun `summary moves to the new month at midnight while observed`() = runTest {
        // 31 октября, 23:30 по Белграду.
        val clock = SchedulerClock(testScheduler, Instant.parse("2026-10-31T22:30:00Z"), ZoneId.of("Europe/Belgrade"))
        val expenses = FakeExpenseRepository()
        val observe = ObserveMonthSummaryUseCase(expenses, FakeIncomeRepository(), FakeRefundRepository(), FakeDebtRepository(expenses), clock)
        val seen = mutableListOf<MonthSummary>()
        backgroundScope.launch { observe(CurrencyCode.RSD).collect { seen += it } }
        runCurrent()
        assertEquals(YearMonth.of(2026, 10), seen.last().month)
        assertEquals(1L, seen.last().daysLeft)

        testScheduler.advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(YearMonth.of(2026, 11), seen.last().month)
        assertEquals(30L, seen.last().daysLeft)
        assertEquals(LocalDate.of(2026, 11, 1), seen.last().today)
    }

    @Test
    fun `month summary counts my share only`() = runTest {
        val clock = java.time.Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneId.of("Europe/Belgrade"))
        val expenses = FakeExpenseRepository()
        val debts = FakeDebtRepository(expenses)
        val rsd = { minor: Long -> app.moneytracker.domain.model.Money(minor, CurrencyCode.RSD) }
        val at = Instant.parse("2026-10-12T18:00:00Z")
        expenses.save(
            app.moneytracker.domain.model.Expense.create(rsd(300000), 1, 1, at, clock.zone, null, at),
            listOf(app.moneytracker.domain.model.ExpenseShare(expenseId = 0, personId = 1, amount = rsd(200000), createdAt = at, updatedAt = at)),
        )
        debts.insertPaidForMe(app.moneytracker.domain.model.PaidForMe.create(rsd(40000), 1, 1, at, clock.zone, null, at))
        val summary = ObserveMonthSummaryUseCase(expenses, FakeIncomeRepository(), FakeRefundRepository(), debts, clock)(CurrencyCode.RSD).first()
        assertEquals(rsd(140000), summary.spent.total)
    }
}

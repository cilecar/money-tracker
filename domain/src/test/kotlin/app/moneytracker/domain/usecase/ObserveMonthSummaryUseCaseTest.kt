package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeIncomeRepository
import app.moneytracker.domain.fake.FakeRefundRepository
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.MonthSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
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
        val observe = ObserveMonthSummaryUseCase(FakeExpenseRepository(), FakeIncomeRepository(), FakeRefundRepository(), clock)
        val seen = mutableListOf<MonthSummary>()
        backgroundScope.launch { observe(CurrencyCode.RSD).collect { seen += it } }
        runCurrent()
        assertEquals(YearMonth.of(2026, 10), seen.last().month)
        assertEquals(1L, seen.last().daysLeft)

        testScheduler.advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(YearMonth.of(2026, 11), seen.last().month)
        assertEquals(30L, seen.last().daysLeft)
    }
}

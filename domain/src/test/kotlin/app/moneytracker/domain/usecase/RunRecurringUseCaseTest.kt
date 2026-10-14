package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeRecurringRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.RecurringRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
    fun `catch up never goes further back than a year, even across runs`() = runTest {
        val id = add("2020-01-01", frequency = Frequency.DAILY)
        // 14.10.2025 … 14.10.2026 включительно. Раньше лимит был на запуск, и каждый следующий
        // запуск добавлял ещё 366 — в сумме 2479 записей.
        assertEquals(366, run() + run() + run())
        assertEquals(LocalDate.parse("2025-10-14"), expenses.items.value.values.minOf { it.localDate })
        assertEquals(LocalDate.parse("2026-10-15"), recurring.get(id)!!.nextRunDate)
    }

    @Test
    fun `template that ended long ago is finished without creating anything`() = runTest {
        val id = add("2020-01-01", end = "2021-01-01", frequency = Frequency.DAILY)
        assertEquals(0, run())
        assertFalse(recurring.get(id)!!.active)
    }

    /** getDue() вернул снимок, а потом [change] изменил шаблон — как удаление или пауза с экрана посреди запуска. */
    private fun runAfter(change: suspend (Long) -> Unit) = RunRecurringUseCase(
        object : RecurringRepository by recurring {
            override suspend fun getDue(today: LocalDate) = recurring.getDue(today).onEach { change(it.id) }
        },
        rates,
        checkBudgets(clock, expenses),
        clock,
    )

    @Test
    fun `template deleted during a run creates nothing`() = runTest {
        add("2026-08-05")
        assertEquals(0, runAfter { recurring.delete(it) }())
        assertEquals(0, expenses.items.value.size)
    }

    @Test
    fun `pause made during a run is not undone`() = runTest {
        val id = add("2026-08-05")
        assertEquals(0, runAfter { recurring.upsert(recurring.get(it)!!.copy(active = false)) }())
        assertEquals(0, expenses.items.value.size)
        assertFalse(recurring.get(id)!!.active)
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

    @Test
    fun `resuming a paused template does not back-fill paused periods`() = runTest {
        // Поставлен на паузу после июльского платежа, возобновлён сегодня (14.10).
        val id = recurring.upsert(template("2026-07-14").copy(nextRunDate = LocalDate.parse("2026-08-14"), active = false))
        save(recurring.get(id)!!.copy(active = true))
        assertEquals(listOf(LocalDate.parse("2026-10-14")), expenses.items.value.values.map { it.localDate })
        assertEquals(LocalDate.parse("2026-11-14"), recurring.get(id)!!.nextRunDate)
    }

    @Test
    fun `changing schedule on the day of a payment does not repeat it`() = runTest {
        val id = save(template("2026-10-14")) // сегодняшний платёж уже создан
        save(recurring.get(id)!!.copy(frequency = Frequency.WEEKLY, dayOfMonth = null))
        assertEquals(1, expenses.items.value.size)
        assertEquals(LocalDate.parse("2026-10-21"), recurring.get(id)!!.nextRunDate)
    }

    @Test
    fun `save racing with a background run does not duplicate`() = runTest {
        val id = recurring.upsert(template("2026-10-14")) // сегодняшний платёж ещё не создан
        val gate = CompletableDeferred<Unit>()
        // get() «зависает» после чтения — как медленный запрос к БД.
        val slow = object : RecurringRepository by recurring {
            override suspend fun get(id: Long) = recurring.get(id).also { gate.await() }
        }
        val run = RunRecurringUseCase(slow, FakeExchangeRateRepository(clock), checkBudgets(clock, expenses), clock)
        val save = SaveRecurringUseCase(slow, run, clock)

        launch { save(recurring.get(id)!!.copy(amount = Money(200, CurrencyCode.EUR))) }
        runCurrent()
        launch { run() } // фоновая задача
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, expenses.items.value.size)
        assertEquals(LocalDate.parse("2026-11-14"), recurring.get(id)!!.nextRunDate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `end before start is rejected`() = runTest {
        save(template("2026-10-14").copy(endDate = LocalDate.parse("2026-10-01")))
    }
}

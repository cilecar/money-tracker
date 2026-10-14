package app.moneytracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.data.rates.RatesApi
import app.moneytracker.data.rates.RemoteRatesDataSource
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.usecase.BudgetNotifier
import app.moneytracker.domain.usecase.CheckBudgetsUseCase
import app.moneytracker.domain.usecase.FillPendingRatesUseCase
import app.moneytracker.domain.usecase.ObserveBudgetsUseCase
import app.moneytracker.domain.usecase.RefreshRatesUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class ExchangeRateRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ExchangeRateRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var refresh: RefreshRatesUseCase

    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var response: () -> JsonObject = { throw IOException("offline") }

    private val api = object : RatesApi {
        override suspend fun get(url: String): JsonObject = response()
    }

    private fun fawaz(date: String, rsd: String, rub: String) =
        Json.parseToJsonElement("""{"date":"$date","eur":{"rsd":$rsd,"rub":$rub}}""").jsonObject

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        repo = ExchangeRateRepositoryImpl(db.exchangeRateDao(), RemoteRatesDataSource(api), clock)
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
        val budgets = BudgetRepositoryImpl(db.budgetDao())
        val check = CheckBudgetsUseCase(
            ObserveBudgetsUseCase(budgets, expenses, CategoryRepositoryImpl(db.categoryDao()), RefundRepositoryImpl(db.refundDao())),
            budgets,
            object : BudgetNotifier {
                override fun notify(progress: BudgetProgress, level: BudgetLevel) = Unit
            },
            clock,
        )
        refresh = RefreshRatesUseCase(repo, FillPendingRatesUseCase(expenses, IncomeRepositoryImpl(db.incomeDao()), RefundRepositoryImpl(db.refundDao()), repo, check, clock), clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun emptyDatabaseHasNoRates() = runTest {
        assertNull(repo.current())
        assertTrue(repo.refresh() is RefreshResult.Failure)
        assertNull(repo.observeCurrent().first())
    }

    @Test
    fun refreshStoresExactRates() = runTest {
        response = { fawaz("2026-10-13", "117.40499356", "93.777076") }
        assertTrue(repo.refresh() is RefreshResult.Success)

        val current = repo.current()!!
        assertEquals(BigDecimal("117.40499356"), current.snapshot.rsdPerEur)
        assertEquals(LocalDate.of(2026, 10, 13), current.snapshot.date)
        assertEquals(now, current.lastSyncAt)
    }

    @Test
    fun manualRateOverridesSameDateAndCanBeReset() = runTest {
        response = { fawaz("2026-10-14", "117.4", "93.7") }
        repo.refresh()
        repo.setManualRate(CurrencyCode.RSD, BigDecimal("116.5"), LocalDate.of(2026, 10, 14))

        val withManual = repo.current()!!
        assertEquals(BigDecimal("116.5"), withManual.snapshot.rsdPerEur)
        assertEquals(RateSource.MANUAL, withManual.effective.getValue(CurrencyCode.RSD).source)

        repo.clearManualRates(CurrencyCode.RSD)
        assertEquals(BigDecimal("117.4"), repo.current()!!.snapshot.rsdPerEur)
    }

    @Test
    fun manualOnlyRatesHaveNoSyncTime() = runTest {
        repo.setManualRate(CurrencyCode.RSD, BigDecimal("117"), LocalDate.of(2026, 10, 14))
        repo.setManualRate(CurrencyCode.RUB, BigDecimal("94"), LocalDate.of(2026, 10, 14))
        val current = repo.current()!!
        assertNull(current.lastSyncAt)
        assertEquals(BigDecimal("117"), current.snapshot.rsdPerEur)
    }

    @Test
    fun repeatedRefreshForSameDateDoesNotDuplicate() = runTest {
        response = { fawaz("2026-10-14", "117.4", "93.7") }
        repo.refresh()
        response = { fawaz("2026-10-14", "117.5", "93.8") }
        repo.refresh()
        assertEquals(2, db.exchangeRateDao().latest().size)
        assertEquals(BigDecimal("117.5"), repo.current()!!.snapshot.rsdPerEur)
    }

    @Test
    fun expenseSavedOfflineGetsRatesAfterRefresh() = runTest {
        val id = expenses.insert(
            Expense.create(Money(1000, CurrencyCode.EUR), 1, 1, now, ZoneOffset.UTC, rates = null, now = now),
        )
        assertNull(expenses.get(id)!!.converted)

        response = { fawaz("2026-10-14", "117.4", "93.7") }
        refresh()

        val filled = expenses.get(id)!!
        assertEquals(117400L, filled.converted!!.rsdMinor)
        assertEquals(LocalDate.of(2026, 10, 14), filled.rates!!.date)
    }
}

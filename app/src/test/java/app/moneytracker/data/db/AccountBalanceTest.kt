package app.moneytracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.repository.AccountRepositoryImpl
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.usecase.ReconcileAccountUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Остаток считает SQL — проверяем запрос на настоящей базе, а не на фейке. */
@RunWith(RobolectricTestRunner::class)
class AccountBalanceTest {

    private lateinit var db: AppDatabase
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl

    private val zone = ZoneId.of("Europe/Belgrade")
    private val clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), zone)
    private val rates = RateSnapshot(BigDecimal("117"), BigDecimal("95"), LocalDate.of(2026, 10, 1))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        accounts = AccountRepositoryImpl(db.accountDao())
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun card(opening: Long = 0, openingDate: LocalDate = LocalDate.of(2026, 10, 1)) = accounts.upsert(
        Account(name = "Карта", type = AccountType.CARD, currency = CurrencyCode.EUR, openingBalanceMinor = opening, openingDate = openingDate),
    )

    private suspend fun spend(account: Long, amount: Money, at: String, withRates: Boolean = true, accountAmount: Long? = null) =
        expenses.insert(
            Expense.create(amount, 1, account, Instant.parse(at), zone, rates.takeIf { withRates }, clock.instant())
                .copy(accountAmountMinor = accountAmount),
        )

    private suspend fun balance(id: Long) = accounts.observeBalances().first().single { it.account.id == id }

    @Test
    fun balanceUsesAccountCurrencyAndOpeningDate() = runTest {
        val id = card(opening = 100_00)
        spend(id, Money(20_00, CurrencyCode.EUR), "2026-10-02T10:00:00Z")
        // 1170 RSD = 10 € по снапшоту.
        spend(id, Money(1170_00, CurrencyCode.RSD), "2026-10-03T10:00:00Z")
        // Банк списал 10.50 € за 1170 RSD — берём фактическую сумму, а не снапшот.
        spend(id, Money(1170_00, CurrencyCode.RSD), "2026-10-04T10:00:00Z", accountAmount = 10_50)
        // До даты начального остатка — уже учтено в нём.
        spend(id, Money(99_00, CurrencyCode.EUR), "2026-09-30T10:00:00Z")

        val b = balance(id)
        assertEquals(Money(100_00 - 20_00 - 10_00 - 10_50, CurrencyCode.EUR), b.balance)
        assertTrue(b.isExact)
    }

    @Test
    fun foreignExpenseWithoutRatesIsCountedSeparately() = runTest {
        val id = card(opening = 50_00)
        spend(id, Money(1000_00, CurrencyCode.RUB), "2026-10-02T10:00:00Z", withRates = false)
        // Без курса, но в валюте счёта — точная сумма известна.
        spend(id, Money(5_00, CurrencyCode.EUR), "2026-10-02T11:00:00Z", withRates = false)

        val b = balance(id)
        assertEquals(Money(45_00, CurrencyCode.EUR), b.balance)
        assertEquals(1, b.unconverted)
        assertFalse(b.isExact)
    }

    @Test
    fun reconcileWritesDifferenceAsAdjustment() = runTest {
        val id = card(opening = 100_00)
        spend(id, Money(30_00, CurrencyCode.EUR), "2026-10-02T10:00:00Z")
        val reconcile = ReconcileAccountUseCase(accounts, clock)

        val adjustment = reconcile(id, Money(65_00, CurrencyCode.EUR), "Сверка")!!
        assertEquals(Money(-5_00, CurrencyCode.EUR), adjustment.amount)
        assertEquals(LocalDate.of(2026, 10, 14), adjustment.localDate)
        assertEquals(Money(65_00, CurrencyCode.EUR), balance(id).balance)
        // Совпадает — новой корректировки нет.
        assertNull(reconcile(id, Money(65_00, CurrencyCode.EUR)))
        assertEquals(1, accounts.observeAdjustments(id).first().size)
        // Корректировка — не расход.
        assertEquals(1, expenses.observeBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).first().size)
    }

    @Test
    fun deletingAccountMovesExpensesAndDropsAdjustments() = runTest {
        val id = card()
        val e = spend(id, Money(10_00, CurrencyCode.RSD), "2026-10-02T10:00:00Z", accountAmount = 9)
        ReconcileAccountUseCase(accounts, clock)(id, Money(1_00, CurrencyCode.EUR))
        assertEquals(1, accounts.usage(id).adjustments)

        accounts.delete(id, moveTo = 1)
        val moved = expenses.get(e)!!
        assertEquals(1L, moved.accountId)
        // Сумма списания была в валюте прежнего счёта.
        assertNull(moved.accountAmountMinor)
        assertEquals(0, db.maintenanceDao().allAdjustments().size)
    }

    @Test
    fun currencyIsLockedOnceAccountHasOperations() = runTest {
        val id = card()
        assertFalse(accounts.hasOperations(id))
        spend(id, Money(1_00, CurrencyCode.EUR), "2026-10-02T10:00:00Z")
        assertTrue(accounts.hasOperations(id))
    }

    @Test
    fun incomesAndRefundsAddToBalance() = runTest {
        val id = card(opening = 10_00)
        val incomes = app.moneytracker.data.repository.IncomeRepositoryImpl(db.incomeDao())
        val refunds = app.moneytracker.data.repository.RefundRepositoryImpl(db.refundDao())
        val e = spend(id, Money(30_00, CurrencyCode.EUR), "2026-10-02T10:00:00Z")
        // 11 700 RSD = 100 € по снапшоту.
        incomes.insert(app.moneytracker.domain.model.Income.create(Money(11700_00, CurrencyCode.RSD), id, 1, Instant.parse("2026-10-03T10:00:00Z"), zone, rates, clock.instant()))
        refunds.insert(app.moneytracker.domain.model.Refund.create(Money(5_00, CurrencyCode.EUR), id, e, 1, Instant.parse("2026-10-04T10:00:00Z"), zone, null, clock.instant()))
        // Доход без курса в чужой валюте — в остаток не вошёл.
        incomes.insert(app.moneytracker.domain.model.Income.create(Money(1_00, CurrencyCode.RUB), id, 1, Instant.parse("2026-10-05T10:00:00Z"), zone, null, clock.instant()))

        val b = balance(id)
        assertEquals(Money(10_00 - 30_00 + 100_00 + 5_00, CurrencyCode.EUR), b.balance)
        assertEquals(1, b.unconverted)
        assertTrue(accounts.hasOperations(id))
        assertEquals(4, accounts.usage(id).expenses)
    }

    @Test
    fun refundOutlivesItsExpense() = runTest {
        val id = card()
        val refunds = app.moneytracker.data.repository.RefundRepositoryImpl(db.refundDao())
        val e = spend(id, Money(30_00, CurrencyCode.EUR), "2026-10-02T10:00:00Z")
        val r = refunds.insert(app.moneytracker.domain.model.Refund.create(Money(5_00, CurrencyCode.EUR), id, e, 1, Instant.parse("2026-10-04T10:00:00Z"), zone, null, clock.instant()))
        expenses.delete(e)
        val refund = refunds.get(r)!!
        assertNull(refund.expenseId)
        assertEquals(1L, refund.categoryId)
        assertEquals(Money(5_00, CurrencyCode.EUR), balance(id).balance)
    }

    private val transfers by lazy { app.moneytracker.data.repository.TransferRepositoryImpl(db) }

    private suspend fun rsdCash(opening: Long = 0) = accounts.upsert(
        Account(name = "Наличные RSD", type = AccountType.CASH, currency = CurrencyCode.RSD, openingBalanceMinor = opening, openingDate = LocalDate.of(2026, 10, 1)),
    )

    private fun transfer(from: Long, to: Long, out: Money, `in`: Money, at: String = "2026-10-05T10:00:00Z") =
        app.moneytracker.domain.model.Transfer.create(from, to, out, `in`, Instant.parse(at), zone, rates, clock.instant())

    /** Обмен EUR → RSD по курсу обменника: каждая сторона меняется на свою сумму, без пересчёта по API. */
    @Test
    fun exchangeTransferMovesBothBalancesByStoredAmounts() = runTest {
        val card = card(opening = 200_00)
        val cash = rsdCash(opening = 1000_00)
        transfers.save(transfer(card, cash, Money(100_00, CurrencyCode.EUR), Money(11650_00, CurrencyCode.RSD)), null)
        // Обратно часть: 2 340 RSD → 20 €.
        transfers.save(transfer(cash, card, Money(2340_00, CurrencyCode.RSD), Money(20_00, CurrencyCode.EUR), "2026-10-06T10:00:00Z"), null)

        assertEquals(Money(200_00 - 100_00 + 20_00, CurrencyCode.EUR), balance(card).balance)
        assertEquals(Money(1000_00 + 11650_00 - 2340_00, CurrencyCode.RSD), balance(cash).balance)
        assertTrue(balance(card).isExact)
        // Итог по счетам — по текущему курсу API (117): разница с курсом обменника видна в итоге.
        val total = app.moneytracker.domain.model.AccountsTotal.of(accounts.observeBalances().first(), CurrencyCode.RSD, rates)
        // Карта 120 € × 117 = 14 040 RSD, наличные 10 310 RSD; счёт «Наличные» из сидера пустой.
        assertEquals(Money(14040_00 + 10310_00, CurrencyCode.RSD), total.total)
    }

    @Test
    fun transferBeforeOpeningDateIsIgnoredOnThatAccountOnly() = runTest {
        val card = card(opening = 50_00, openingDate = LocalDate.of(2026, 10, 10))
        val cash = rsdCash()
        transfers.save(transfer(card, cash, Money(10_00, CurrencyCode.EUR), Money(1170_00, CurrencyCode.RSD), "2026-10-05T10:00:00Z"), null)
        // Для карты перевод уже учтён в начальном остатке, для наличных — нет.
        assertEquals(Money(50_00, CurrencyCode.EUR), balance(card).balance)
        assertEquals(Money(1170_00, CurrencyCode.RSD), balance(cash).balance)
    }

    @Test
    fun feeIsStoredAtomicallyAndDeletedWithTransfer() = runTest {
        val card = card(opening = 100_00)
        val cash = rsdCash()
        val fee = Expense.create(Money(1_50, CurrencyCode.EUR), 1, card, Instant.parse("2026-10-05T10:00:00Z"), zone, rates, clock.instant())
        val id = transfers.save(transfer(card, cash, Money(10_00, CurrencyCode.EUR), Money(1165_00, CurrencyCode.RSD)), fee)
        val feeId = transfers.get(id)!!.feeExpenseId!!
        // Комиссия — расход со счёта источника.
        assertEquals(Money(100_00 - 10_00 - 1_50, CurrencyCode.EUR), balance(card).balance)
        assertEquals(1, accounts.usage(card).transfers)
        assertEquals(1, accounts.usage(cash).transfers)

        // Комиссию убрали.
        transfers.save(transfers.get(id)!!, null)
        assertNull(expenses.get(feeId))
        assertEquals(Money(90_00, CurrencyCode.EUR), balance(card).balance)

        transfers.delete(id)
        assertNull(transfers.get(id))
        assertEquals(Money(100_00, CurrencyCode.EUR), balance(card).balance)
    }

    // 1 EUR = 117 RSD = 95 RUB = 1.17 USDT
    private val usdtRates = RateSnapshot(BigDecimal("117"), BigDecimal("95"), LocalDate.of(2026, 10, 1), usdtPerEur = BigDecimal("1.17"))

    private suspend fun usdtCard(opening: Long = 0) = accounts.upsert(
        Account(name = "Bybit", type = AccountType.CARD, currency = CurrencyCode.USDT, openingBalanceMinor = opening, openingDate = LocalDate.of(2026, 10, 1)),
    )

    private fun usdt(minor: Long) = Money(minor, CurrencyCode.USDT)

    /**
     * У USDT нет колонки usdt_minor: остаток берёт сумму списания, а без неё пересчитывает по снапшоту операции.
     * Снапшот без курса USDT (запись до USDT) пересчитать нельзя — операция в unconverted.
     */
    @Test
    fun usdtAccountBalanceUsesAccountAmountThenSnapshot() = runTest {
        val id = usdtCard(opening = 100_00)
        suspend fun spendUsdt(amount: Money, at: String, snapshot: RateSnapshot?, accountAmount: Long? = null) = expenses.insert(
            Expense.create(amount, 1, id, Instant.parse(at), zone, snapshot, clock.instant()).copy(accountAmountMinor = accountAmount),
        )
        spendUsdt(usdt(10_00), "2026-10-02T10:00:00Z", usdtRates)
        // Карта списала 10.20 USDT за 1170 RSD — фактическая сумма, а не снапшот.
        spendUsdt(Money(1170_00, CurrencyCode.RSD), "2026-10-03T10:00:00Z", usdtRates, accountAmount = 10_20)
        // Без суммы списания: 1170 RSD × 1.17 / 117 = 11.70 USDT.
        spendUsdt(Money(1170_00, CurrencyCode.RSD), "2026-10-04T10:00:00Z", usdtRates)
        // Снапшот без курса USDT — пересчитать не по чему.
        spendUsdt(Money(5_00, CurrencyCode.EUR), "2026-10-05T10:00:00Z", rates)
        // До даты начального остатка.
        spendUsdt(usdt(99_00), "2026-09-30T10:00:00Z", usdtRates)
        val incomes = app.moneytracker.data.repository.IncomeRepositoryImpl(db.incomeDao())
        val refunds = app.moneytracker.data.repository.RefundRepositoryImpl(db.refundDao())
        // Доход 10 € = 11.70 USDT, возврат 95 RUB = 1.17 USDT.
        incomes.insert(app.moneytracker.domain.model.Income.create(Money(10_00, CurrencyCode.EUR), id, 1, Instant.parse("2026-10-06T10:00:00Z"), zone, usdtRates, clock.instant()))
        refunds.insert(app.moneytracker.domain.model.Refund.create(Money(95_00, CurrencyCode.RUB), id, null, 1, Instant.parse("2026-10-07T10:00:00Z"), zone, usdtRates, clock.instant()))

        val b = balance(id)
        assertEquals(usdt(100_00 - 10_00 - 10_20 - 11_70 + 11_70 + 1_17), b.balance)
        assertEquals(1, b.unconverted)

        // Обратное направление: USDT-расход с RSD-счёта идёт через готовую rsd_minor.
        val cash = rsdCash(opening = 2000_00)
        expenses.insert(Expense.create(usdt(10_00), 1, cash, Instant.parse("2026-10-02T10:00:00Z"), zone, usdtRates, clock.instant()))
        assertEquals(Money(2000_00 - 1000_00, CurrencyCode.RSD), balance(cash).balance)
        assertTrue(balance(cash).isExact)

        // Итог в RSD по текущему курсу: 80.97 USDT × 117 / 1.17 = 8 097 RSD.
        val total = app.moneytracker.domain.model.AccountsTotal.of(accounts.observeBalances().first(), CurrencyCode.RSD, usdtRates)
        assertEquals(Money(8097_00 + 1000_00, CurrencyCode.RSD), total.total)
    }

    /** USDT → RSD в обменнике и обратно: обе суммы как ввели, без пересчёта по API. */
    @Test
    fun usdtToRsdTransferMovesBothBalancesByStoredAmounts() = runTest {
        val card = usdtCard(opening = 100_00)
        val cash = rsdCash()
        transfers.save(app.moneytracker.domain.model.Transfer.create(card, cash, usdt(50_00), Money(4900_00, CurrencyCode.RSD), Instant.parse("2026-10-05T10:00:00Z"), zone, usdtRates, clock.instant()), null)
        transfers.save(app.moneytracker.domain.model.Transfer.create(cash, card, Money(1000_00, CurrencyCode.RSD), usdt(9_80), Instant.parse("2026-10-06T10:00:00Z"), zone, usdtRates, clock.instant()), null)

        assertEquals(usdt(100_00 - 50_00 + 9_80), balance(card).balance)
        assertEquals(Money(4900_00 - 1000_00, CurrencyCode.RSD), balance(cash).balance)
        assertTrue(balance(card).isExact)
        val stored = transfers.get(db.transferDao().getAll().single { it.fromCurrency == CurrencyCode.USDT }.id)!!
        assertEquals(BigDecimal("1.17"), stored.apiRates!!.usdtPerEur)
        assertEquals(0, BigDecimal("98").compareTo(stored.exchangeRate))
    }
}

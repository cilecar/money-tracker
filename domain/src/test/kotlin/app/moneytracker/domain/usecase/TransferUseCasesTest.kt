package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeAccountRepository
import app.moneytracker.domain.fake.FakeCategoryRepository
import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeTransferRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.OperationKind
import app.moneytracker.domain.model.Transfer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TransferUseCasesTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val opening = LocalDate.of(2026, 1, 1)
    private val eurCard = Account(id = 1, name = "Bybit", type = AccountType.CARD, currency = CurrencyCode.EUR, openingDate = opening)
    private val rsdCash = Account(id = 2, name = "Наличные", type = AccountType.CASH, currency = CurrencyCode.RSD, openingDate = opening)
    private val usdtCard = Account(id = 3, name = "Bybit USDT", type = AccountType.CARD, currency = CurrencyCode.USDT, openingDate = opening)
    private val accounts = FakeAccountRepository(listOf(eurCard, rsdCash, usdtCard))
    private val expenses = FakeExpenseRepository()
    private val categories = FakeCategoryRepository(listOf(Category(id = 11, name = "Комиссии", defaultKey = "fees", iconKey = "receipt", color = 0)))
    private val rates = FakeExchangeRateRepository(clock)
    private val transfers = FakeTransferRepository(expenses)
    private val save = SaveTransferUseCase(transfers, accounts, expenses, categories, rates, checkBudgets(clock, expenses, categories), clock)
    private val delete = DeleteTransferUseCase(transfers, expenses, checkBudgets(clock, expenses, categories))

    private fun eur(minor: Long) = Money(minor, CurrencyCode.EUR)
    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

    private suspend fun exchange(fee: Money? = null) = (save(TransferInput(fromAccountId = 1, toAccountId = 2, fromAmount = eur(100_00), toAmount = rsd(11650_00), fee = fee, occurredAt = now)) as TransferResult.Success).id

    @Test
    fun `exchange keeps both amounts and shows actual rate next to api`() = runTest {
        rates.setManualRate(CurrencyCode.RSD, BigDecimal("117.20"), LocalDate.of(2026, 10, 14))
        rates.setManualRate(CurrencyCode.RUB, BigDecimal("95"), LocalDate.of(2026, 10, 14))
        val t = transfers.get(exchange())!!
        assertEquals(eur(100_00), t.fromAmount)
        assertEquals(rsd(11650_00), t.toAmount)
        assertEquals(0, BigDecimal("116.5").compareTo(t.exchangeRate))
        assertEquals(0, BigDecimal("117.20").compareTo(t.apiRate))
        // Обменник дал на 0.60% меньше API.
        assertEquals(BigDecimal("-0.60"), Transfer.deviationPercent(t.exchangeRate, t.apiRate))
    }

    @Test
    fun `usdt to rsd exchange keeps both amounts as entered`() = runTest {
        rates.setManualRate(CurrencyCode.RSD, BigDecimal("117.15"), LocalDate.of(2026, 10, 14))
        rates.setManualRate(CurrencyCode.RUB, BigDecimal("95"), LocalDate.of(2026, 10, 14))
        rates.setManualRate(CurrencyCode.USDT, BigDecimal("1.165"), LocalDate.of(2026, 10, 14))
        val usdt = Money(50_00, CurrencyCode.USDT)
        val fee = Money(1_00, CurrencyCode.USDT)
        val id = (save(TransferInput(fromAccountId = 3, toAccountId = 2, fromAmount = usdt, toAmount = rsd(4900_00), fee = fee, occurredAt = now)) as TransferResult.Success).id

        val t = transfers.get(id)!!
        assertEquals(usdt, t.fromAmount)
        assertEquals(rsd(4900_00), t.toAmount)
        assertEquals(0, BigDecimal("98").compareTo(t.exchangeRate))
        // API: 117.15 / 1.165 = 100.5579… RSD за 1 USDT; обменник дал на 2.54% меньше.
        assertEquals(BigDecimal("-2.54"), Transfer.deviationPercent(t.exchangeRate, t.apiRate))
        // Комиссия в USDT — обычный расход с курсом USDT в снапшоте.
        val feeExpense = expenses.get(t.feeExpenseId!!)!!
        assertEquals(fee, feeExpense.amount)
        assertEquals(86L, feeExpense.converted!!.eurMinor) // 1 / 1.165 = 0.858…

        // Обратно RSD → USDT тоже без пересчёта.
        val back = (save(TransferInput(fromAccountId = 2, toAccountId = 3, fromAmount = rsd(1000_00), toAmount = Money(9_80, CurrencyCode.USDT), occurredAt = now)) as TransferResult.Success).id
        assertEquals(Money(9_80, CurrencyCode.USDT), transfers.get(back)!!.toAmount)
    }

    @Test
    fun `transfer without usdt rate has no api rate to compare`() = runTest {
        rates.setManualRate(CurrencyCode.RSD, BigDecimal("117.15"), LocalDate.of(2026, 10, 14))
        rates.setManualRate(CurrencyCode.RUB, BigDecimal("95"), LocalDate.of(2026, 10, 14))
        val id = (save(TransferInput(fromAccountId = 3, toAccountId = 2, fromAmount = Money(50_00, CurrencyCode.USDT), toAmount = rsd(4900_00), occurredAt = now)) as TransferResult.Success).id
        assertNull(transfers.get(id)!!.apiRate)
    }

    @Test
    fun `fee becomes an expense in fees category from the source account`() = runTest {
        val id = exchange(fee = eur(1_50))
        val t = transfers.get(id)!!
        val fee = expenses.get(t.feeExpenseId!!)!!
        assertEquals(eur(1_50), fee.amount)
        assertEquals(11L, fee.categoryId)
        assertEquals(1L, fee.accountId)
        // Комиссия — обычный расход; сам перевод в расходы не попадает.
        assertEquals(1, expenses.observe(ExpenseFilter()).first().size)

        // Убрали комиссию при правке — расход удалён.
        save(TransferInput(id = id, fromAccountId = 1, toAccountId = 2, fromAmount = eur(100_00), toAmount = rsd(11650_00), occurredAt = now))
        assertNull(transfers.get(id)!!.feeExpenseId)
        assertTrue(expenses.observe(ExpenseFilter()).first().isEmpty())
    }

    @Test
    fun `editing fee updates the same expense, deleting transfer removes it`() = runTest {
        val id = exchange(fee = eur(1_00))
        val feeId = transfers.get(id)!!.feeExpenseId!!
        save(TransferInput(id = id, fromAccountId = 1, toAccountId = 2, fromAmount = eur(100_00), toAmount = rsd(11650_00), fee = eur(2_00), occurredAt = now))
        assertEquals(feeId, transfers.get(id)!!.feeExpenseId)
        assertEquals(eur(2_00), expenses.get(feeId)!!.amount)

        val (deleted, fee) = delete(id)!!
        assertEquals(id, deleted.id)
        assertEquals(feeId, fee!!.id)
        assertNull(transfers.get(id))
        assertNull(expenses.get(feeId))
        // Undo: тот же id у перевода и комиссии.
        transfers.save(deleted, fee)
        assertEquals(feeId, transfers.get(id)!!.feeExpenseId)
        assertEquals(eur(2_00), expenses.get(feeId)!!.amount)
    }

    @Test
    fun `fees category is recreated if the user deleted it`() = runTest {
        categories.items.value = emptyList()
        val id = exchange(fee = eur(1_00))
        val fee = expenses.get(transfers.get(id)!!.feeExpenseId!!)!!
        assertEquals("fees", categories.getAll().single { it.id == fee.categoryId }.defaultKey)
    }

    @Test
    fun `validation - same account and currency mismatch`() = runTest {
        assertEquals(
            TransferError.SAME_ACCOUNT,
            (save(TransferInput(fromAccountId = 1, toAccountId = 1, fromAmount = eur(1), toAmount = eur(1), occurredAt = now)) as TransferResult.Error).error,
        )
        assertEquals(
            TransferError.CURRENCY_MISMATCH,
            (save(TransferInput(fromAccountId = 1, toAccountId = 2, fromAmount = rsd(1), toAmount = rsd(1), occurredAt = now)) as TransferResult.Error).error,
        )
        assertEquals(
            TransferError.CURRENCY_MISMATCH,
            (save(TransferInput(fromAccountId = 1, toAccountId = 2, fromAmount = eur(1), toAmount = rsd(1), fee = rsd(1), occurredAt = now)) as TransferResult.Error).error,
        )
    }

    @Test
    fun `account with transfers can only be archived`() = runTest {
        accounts.upsert(rsdCash.copy(id = 3, name = "Ещё"))
        val repo = object : app.moneytracker.domain.repository.AccountRepository by accounts {
            override suspend fun usage(id: Long) = app.moneytracker.domain.model.AccountUsage(transfers = 1)
        }
        val result = DeleteAccountUseCase(repo, app.moneytracker.domain.fake.FakeSettingsRepository())(1, moveTo = 3)
        assertEquals(AccountError.HAS_TRANSFERS, (result as AccountResult.Error).error)
    }

    @Test
    fun `feed filter - transfer matches either account and hides under category filters`() {
        val t = Transfer.create(1, 2, eur(100), rsd(11700), now, zone, null, now, "обмен")
        assertTrue(ExpenseFilter().matches(t))
        assertTrue(ExpenseFilter(accountIds = setOf(2)).matches(t))
        assertFalse(ExpenseFilter(accountIds = setOf(3)).matches(t))
        assertFalse(ExpenseFilter(categoryIds = setOf(1)).matches(t))
        assertFalse(ExpenseFilter(kinds = setOf(OperationKind.EXPENSE)).matches(t))
        assertTrue(ExpenseFilter(query = "ОБМЕН").matches(t))
    }
}

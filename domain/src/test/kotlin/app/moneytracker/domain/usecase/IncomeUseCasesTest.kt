package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeIncomeCategoryRepository
import app.moneytracker.domain.fake.FakeIncomeRepository
import app.moneytracker.domain.fake.FakeRefundRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IncomeUseCasesTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-14T10:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val rates = FakeExchangeRateRepository(clock)
    private val snapshot = RateSnapshot(BigDecimal("117"), BigDecimal("95"), LocalDate.of(2026, 10, 14))
    private val incomes = FakeIncomeRepository()
    private val refunds = FakeRefundRepository()
    private val expenses = FakeExpenseRepository()
    private val saveIncome = SaveIncomeUseCase(incomes, rates, clock)
    private val saveRefund = SaveRefundUseCase(refunds, expenses, rates, checkBudgets(clock, expenses, refunds = refunds), clock)

    private suspend fun withRates() = rates.setManualRate(CurrencyCode.RSD, snapshot.rsdPerEur, snapshot.date)
        .also { rates.setManualRate(CurrencyCode.RUB, snapshot.rubPerEur, snapshot.date) }

    @Test
    fun `income snapshots current rates and keeps them on edit`() = runTest {
        withRates()
        val id = saveIncome(IncomeInput(amount = Money(100_00, CurrencyCode.EUR), accountId = 1, categoryId = 1, occurredAt = now, comment = " Зарплата "))
        val saved = incomes.get(id)!!
        assertEquals(snapshot.rsdPerEur, saved.rates?.rsdPerEur)
        assertEquals(Money(11700_00, CurrencyCode.RSD), saved.amountIn(CurrencyCode.RSD))
        assertEquals("Зарплата", saved.comment)

        rates.setManualRate(CurrencyCode.RSD, BigDecimal("200"), LocalDate.of(2026, 10, 15))
        saveIncome(IncomeInput(id = id, amount = Money(200_00, CurrencyCode.EUR), accountId = 1, categoryId = 2, occurredAt = now))
        val edited = incomes.get(id)!!
        assertEquals(snapshot.rsdPerEur, edited.rates?.rsdPerEur)
        assertEquals(Money(23400_00, CurrencyCode.RSD), edited.amountIn(CurrencyCode.RSD))
        assertEquals(2L, edited.categoryId)
    }

    @Test
    fun `refund takes category from the expense`() = runTest {
        val expenseId = expenses.insert(Expense.create(Money(500_00, CurrencyCode.RSD), 7, 1, now, zone, null, now))
        val id = saveRefund(RefundInput(amount = Money(200_00, CurrencyCode.RSD), accountId = 1, expenseId = expenseId, categoryId = 3, occurredAt = now))
        val refund = refunds.get(id)!!
        assertEquals(7L, refund.categoryId)
        assertEquals(expenseId, refund.expenseId)
        assertNull(refund.rates)
    }

    @Test
    fun `refund without expense needs a category and keeps it after the expense is gone`() = runTest {
        val manual = saveRefund(RefundInput(amount = Money(1_00, CurrencyCode.EUR), accountId = 1, categoryId = 3, occurredAt = now))
        assertEquals(3L, refunds.get(manual)!!.categoryId)

        val expenseId = expenses.insert(Expense.create(Money(500_00, CurrencyCode.RSD), 7, 1, now, zone, null, now))
        val linked = saveRefund(RefundInput(amount = Money(1_00, CurrencyCode.RSD), accountId = 1, expenseId = expenseId, occurredAt = now))
        expenses.delete(expenseId)
        saveRefund(RefundInput(id = linked, amount = Money(2_00, CurrencyCode.RSD), accountId = 1, occurredAt = now))
        assertEquals(7L, refunds.get(linked)!!.categoryId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refund needs expense or category`() = runTest {
        saveRefund(RefundInput(amount = Money(1_00, CurrencyCode.EUR), accountId = 1, occurredAt = now))
    }

    @Test
    fun `fill pending rates covers incomes and refunds`() = runTest {
        saveIncome(IncomeInput(amount = Money(1_00, CurrencyCode.EUR), accountId = 1, categoryId = 1, occurredAt = now))
        saveRefund(RefundInput(amount = Money(1_00, CurrencyCode.EUR), accountId = 1, categoryId = 1, occurredAt = now))
        withRates()
        val fill = FillPendingRatesUseCase(expenses, incomes, refunds, rates, checkBudgets(clock, expenses, refunds = refunds), clock)
        assertEquals(2, fill())
        assertTrue(incomes.items.value.all { it.rates != null })
        assertTrue(refunds.items.value.all { it.rates != null })
    }

    @Test
    fun `income categories - names unique, last one stays, used one needs a target`() = runTest {
        val repo = FakeIncomeCategoryRepository(
            listOf(IncomeCategory(id = 1, name = "Зарплата", iconKey = "", color = 0), IncomeCategory(id = 2, name = "Подарок", iconKey = "", color = 0, sortOrder = 1)),
        )
        val save = SaveIncomeCategoryUseCase(repo)
        val archive = ArchiveIncomeCategoryUseCase(repo)
        val delete = DeleteIncomeCategoryUseCase(repo)
        assertEquals(IncomeCategoryError.DUPLICATE_NAME, (save(IncomeCategory(name = " зарплата", iconKey = "", color = 0)) as IncomeCategoryResult.Error).error)
        assertEquals(IncomeCategoryError.EMPTY_NAME, (save(IncomeCategory(name = " ", iconKey = "", color = 0)) as IncomeCategoryResult.Error).error)

        repo.incomeCounts[2] = 3
        assertEquals(IncomeCategoryError.NEEDS_TARGET, (delete(2) as IncomeCategoryResult.Error).error)
        assertTrue(delete(2, moveTo = 1) is IncomeCategoryResult.Success)
        assertEquals(3, repo.countIncomes(1))
        assertEquals(IncomeCategoryError.LAST_ACTIVE, (archive(1, true) as IncomeCategoryResult.Error).error)
        assertEquals(IncomeCategoryError.LAST_ACTIVE, (delete(1) as IncomeCategoryResult.Error).error)
    }
}

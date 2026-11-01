package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Доли «мне должны», «за меня заплатили» и проверка разделения. */
class DebtTest {

    // 1 EUR = 100 RSD = 100 RUB
    private val rates = RateSnapshot(BigDecimal("100"), BigDecimal("100"), LocalDate.of(2026, 10, 1))
    private val at = Instant.parse("2026-10-12T19:00:00Z")

    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

    private fun dinner(withRates: Boolean = true) =
        Expense.create(rsd(300000), 5, 2, at, ZoneOffset.UTC, rates.takeIf { withRates }, at).copy(id = 7)

    private fun share(amount: Money = rsd(100000)) =
        ExpenseShare(id = 3, expenseId = 7, personId = 1, amount = amount, createdAt = at, updatedAt = at)

    @Test
    fun `share as negative expense takes date category account and rates of expense`() {
        val expense = dinner()
        val negative = share().asNegativeExpense(expense)
        assertEquals(rsd(-100000), negative.amount)
        assertEquals(-3L, negative.id)
        assertEquals(expense.categoryId, negative.categoryId)
        assertEquals(expense.accountId, negative.accountId)
        assertEquals(expense.localDate, negative.localDate)
        assertEquals(expense.rates, negative.rates)
        assertEquals(-(rates.convertAll(rsd(100000))), negative.converted)
    }

    @Test
    fun `share of expense without rates has no converted`() {
        val negative = share().asNegativeExpense(dinner(withRates = false))
        assertNull(negative.rates)
        assertNull(negative.converted)
    }

    @Test
    fun `share of another expense is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { share().asNegativeExpense(dinner().copy(id = 8)) }
    }

    @Test
    fun `paid for me as expense uses NO_ACCOUNT`() {
        val p = PaidForMe.create(rsd(40000), personId = 1, categoryId = 5, occurredAt = at, zone = ZoneOffset.UTC, rates = rates, now = at)
        val e = p.asExpense()
        assertEquals(PaidForMe.NO_ACCOUNT, e.accountId)
        assertEquals(rsd(40000), e.amount)
        assertEquals(5L, e.categoryId)
        assertEquals(LocalDate.of(2026, 10, 12), e.localDate)
        assertEquals(rates.convertAll(rsd(40000)), e.converted)
    }

    @Test
    fun `paid for me in USDT without USDT rate waits for rates`() {
        val p = PaidForMe.create(Money(1000, CurrencyCode.USDT), 1, 5, at, ZoneOffset.UTC, rates, at)
        assertNull(p.rates)
        assertNull(p.converted)
    }

    @Test
    fun `valid shares`() {
        assertNull(validateShares(rsd(300000), listOf(ShareInput(1, rsd(100000)), ShareInput(2, rsd(100000)))))
        // Моя доля может быть нулевой: купил целиком для другого.
        assertNull(validateShares(rsd(300000), listOf(ShareInput(1, rsd(300000)))))
        assertNull(validateShares(rsd(300000), emptyList()))
    }

    @Test
    fun `invalid shares`() {
        assertEquals(ShareError.EXCEEDS_TOTAL, validateShares(rsd(300000), listOf(ShareInput(1, rsd(200000)), ShareInput(2, rsd(100100)))))
        assertEquals(ShareError.NOT_POSITIVE, validateShares(rsd(300000), listOf(ShareInput(1, rsd(0)))))
        assertEquals(ShareError.WRONG_CURRENCY, validateShares(rsd(300000), listOf(ShareInput(1, Money(100, CurrencyCode.EUR)))))
        assertEquals(ShareError.DUPLICATE_PERSON, validateShares(rsd(300000), listOf(ShareInput(1, rsd(100)), ShareInput(1, rsd(100)))))
    }

    @Test
    fun `split equally gives remainder to me`() {
        val parts = splitEqually(rsd(100000), 2)
        assertEquals(listOf(rsd(33333), rsd(33333)), parts)
        assertEquals(rsd(33334), rsd(100000) - parts.fold(rsd(0)) { a, b -> a + b })
        assertEquals(emptyList<Money>(), splitEqually(rsd(100000), 0))
    }

    @Test
    fun `settlement rejects zero amount`() {
        assertThrows(IllegalArgumentException::class.java) {
            Settlement(accountId = 1, amount = rsd(0), occurredAt = at, localDate = LocalDate.of(2026, 10, 12))
        }
    }

    @Test
    fun `debt of share and of paid for me`() {
        val expense = dinner()
        val settled = share().copy(settlement = Settlement.create(2, rsd(100000), at, ZoneOffset.UTC))
        val owed = Debt.of(settled, expense)
        assertEquals(DebtRef(DebtDirection.OWED_TO_ME, 3), owed.ref)
        assertEquals(expense.accountId, owed.expenseAccountId)
        assertEquals(expense.localDate, owed.localDate)
        assertTrue(owed.isSettled)

        val p = PaidForMe.create(rsd(40000), 1, 5, at, ZoneOffset.UTC, rates, at, comment = " кино ").copy(id = 9)
        val mine = Debt.of(p)
        assertEquals(DebtRef(DebtDirection.I_OWE, 9), mine.ref)
        assertNull(mine.expenseAccountId)
        assertEquals("кино", mine.comment)
    }

    private val petya = Person(id = 1, name = "Петя")
    private val vasya = Person(id = 2, name = "Вася")
    private val persons = listOf(petya, vasya).associateBy { it.id }

    private fun debt(id: Long, direction: DebtDirection, amount: Money, person: Long = 1, settled: Boolean = false) = Debt(
        ref = DebtRef(direction, id), personId = person, amount = amount, categoryId = 5, localDate = LocalDate.of(2026, 10, 12),
        comment = null, expenseAccountId = null, settlement = if (settled) Settlement.create(1, rsd(100), at, ZoneOffset.UTC) else null,
    )

    @Test
    fun `summary nets per person and totals both directions`() {
        val summary = DebtSummary.of(
            listOf(
                debt(1, DebtDirection.OWED_TO_ME, rsd(100000)),
                debt(2, DebtDirection.OWED_TO_ME, rsd(50000)),
                debt(3, DebtDirection.I_OWE, rsd(40000)),
                debt(4, DebtDirection.I_OWE, rsd(20000), person = 2),
                // Погашенные не учитываются.
                debt(5, DebtDirection.OWED_TO_ME, rsd(999900), settled = true),
            ),
            persons, CurrencyCode.RSD, rates,
        )
        assertEquals(rsd(150000), summary.owedToMe)
        assertEquals(rsd(60000), summary.iOwe)
        assertEquals(listOf(petya to rsd(110000), vasya to rsd(-20000)), summary.people.map { it.person to it.net })
        assertEquals(listOf(3, 1), summary.people.map { it.openCount })
        assertTrue(!summary.missingRates)
    }

    @Test
    fun `summary converts by current rates and flags missing ones`() {
        val eurDebt = debt(1, DebtDirection.OWED_TO_ME, Money(1000, CurrencyCode.EUR))
        assertEquals(rsd(100000), DebtSummary.of(listOf(eurDebt), persons, CurrencyCode.RSD, rates).owedToMe)

        val noRates = DebtSummary.of(listOf(eurDebt, debt(2, DebtDirection.I_OWE, rsd(500))), persons, CurrencyCode.RSD, null)
        assertTrue(noRates.missingRates)
        assertEquals(rsd(0), noRates.owedToMe)
        assertEquals(rsd(500), noRates.iOwe)
        assertTrue(noRates.people.single().missingRates)
    }

    @Test
    fun `people without open debts are hidden`() {
        val summary = DebtSummary.of(listOf(debt(1, DebtDirection.OWED_TO_ME, rsd(100), settled = true)), persons, CurrencyCode.RSD, rates)
        assertEquals(emptyList<PersonBalance>(), summary.people)
        assertEquals(rsd(0), summary.owedToMe)
    }
}

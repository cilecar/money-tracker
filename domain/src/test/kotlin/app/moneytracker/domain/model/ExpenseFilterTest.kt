package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExpenseFilterTest {

    private val categories = listOf(
        Category(id = 1, name = "Еда", iconKey = "", color = 0),
        Category(id = 2, name = "Кафе", iconKey = "", color = 0, parentId = 1),
        Category(id = 3, name = "Доставка", iconKey = "", color = 0, parentId = 1),
        Category(id = 4, name = "Транспорт", iconKey = "", color = 0),
    )

    @Test
    fun `parent selection includes subcategories`() {
        assertEquals(setOf(1L, 2L, 3L), ExpenseFilter(categoryIds = setOf(1)).withSubcategories(categories).categoryIds)
    }

    @Test
    fun `subcategory selection stays narrow`() {
        assertEquals(setOf(2L), ExpenseFilter(categoryIds = setOf(2)).withSubcategories(categories).categoryIds)
    }

    @Test
    fun `active count`() {
        assertTrue(ExpenseFilter().isEmpty)
        val f = ExpenseFilter(query = "кофе", from = LocalDate.EPOCH, minAmount = Money(1, CurrencyCode.RSD), maxAmount = Money(5, CurrencyCode.RSD))
        assertEquals(3, f.activeCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `amount bounds in different currencies are rejected`() {
        ExpenseFilter(minAmount = Money(1, CurrencyCode.RSD), maxAmount = Money(5, CurrencyCode.EUR))
    }

    private val at = Instant.parse("2026-10-14T12:00:00Z")
    private val paid = PaidForMe.create(Money(40000, CurrencyCode.RSD), 1, 2, at, ZoneOffset.UTC, null, at, comment = "кино")
    private val settled = Debt(
        ref = DebtRef(DebtDirection.OWED_TO_ME, 1), personId = 1, amount = Money(100000, CurrencyCode.RSD), categoryId = 2,
        localDate = LocalDate.of(2026, 10, 10), comment = "ужин", expenseAccountId = 1,
        settlement = Settlement.create(5, Money(100000, CurrencyCode.RSD), at, ZoneOffset.UTC),
    )

    @Test
    fun `paid for me matches category and hides under account filter`() {
        assertTrue(ExpenseFilter().matches(paid))
        assertTrue(ExpenseFilter(categoryIds = setOf(2)).matches(paid))
        assertFalse(ExpenseFilter(categoryIds = setOf(4)).matches(paid))
        assertFalse(ExpenseFilter(accountIds = setOf(1)).matches(paid))
        assertFalse(ExpenseFilter(kinds = setOf(OperationKind.EXPENSE)).matches(paid))
        assertTrue(ExpenseFilter(kinds = setOf(OperationKind.DEBT), query = "КИН").matches(paid))
    }

    @Test
    fun `settlement matches its settle account and date`() {
        assertTrue(ExpenseFilter().matches(settled))
        assertTrue(ExpenseFilter(accountIds = setOf(5)).matches(settled))
        assertFalse(ExpenseFilter(accountIds = setOf(1)).matches(settled))
        assertFalse(ExpenseFilter(categoryIds = setOf(2)).matches(settled))
        assertTrue(ExpenseFilter(from = LocalDate.of(2026, 10, 14)).matches(settled))
        assertFalse(ExpenseFilter(to = LocalDate.of(2026, 10, 13)).matches(settled))
    }
}

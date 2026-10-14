package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
}

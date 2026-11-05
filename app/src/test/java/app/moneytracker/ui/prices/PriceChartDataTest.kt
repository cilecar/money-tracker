package app.moneytracker.ui.prices

import app.moneytracker.domain.receipt.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class PriceChartDataTest {
    private fun point(chain: Long, day: Int, hour: Int, price: Long) = PricePoint(
        productId = 1, articleId = chain, chainId = chain, storeId = chain, receiptId = day * 100L + hour, expenseId = null,
        occurredAt = LocalDate.of(2026, 11, day).atTime(hour, 0).toInstant(ZoneOffset.UTC),
        date = LocalDate.of(2026, 11, day), quantityMilli = 1000, unitPriceMinor = price, totalMinor = price,
    )

    @Test
    fun `one series per chain sorted by date`() {
        val series = chartSeries(listOf(point(2, 3, 10, 21999), point(1, 5, 10, 20999), point(1, 1, 10, 19999)))
        assertEquals(listOf(1L, 2L), series.map { it.chainId })
        assertEquals(listOf(LocalDate.of(2026, 11, 1).toEpochDay(), LocalDate.of(2026, 11, 5).toEpochDay()), series[0].x)
        assertEquals(listOf(BigDecimal("199.99"), BigDecimal("209.99")), series[0].y)
    }

    @Test
    fun `two purchases on the same day keep the later price`() {
        val series = chartSeries(listOf(point(1, 5, 18, 20999), point(1, 5, 9, 19999)))
        assertEquals(1, series.single().x.size)
        assertEquals(BigDecimal("209.99"), series.single().y.single())
    }
}

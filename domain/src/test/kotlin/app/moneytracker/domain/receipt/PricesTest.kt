package app.moneytracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PricesTest {
    private val lidl = 1L
    private val maxi = 2L

    private fun point(chain: Long, store: Long, day: Int, price: Long, product: Long = 10, hour: Int = 12) = PricePoint(
        productId = product, articleId = product * 10 + chain, chainId = chain, storeId = store, receiptId = day * 100L + hour,
        expenseId = null, occurredAt = LocalDate.of(2026, 11, day).atTime(hour, 0).toInstant(ZoneOffset.UTC),
        date = LocalDate.of(2026, 11, day), quantityMilli = 1000, unitPriceMinor = price, totalMinor = price,
    )

    @Test
    fun `byChain groups and computes last min max`() {
        val points = listOf(
            point(lidl, 11, 5, 20999),
            point(lidl, 11, 1, 19999),
            point(maxi, 21, 2, 21999),
            point(lidl, 12, 3, 18999),
        )
        val chains = Prices.byChain(points)
        assertEquals(listOf(lidl, maxi), chains.map { it.chainId })
        val l = chains[0]
        assertEquals(20999L, l.lastMinor)
        assertEquals(18999L, l.minMinor)
        assertEquals(20999L, l.maxMinor)
        assertEquals(LocalDate.of(2026, 11, 5), l.lastDate)
        assertEquals(listOf(11L, 12L), l.stores.map { it.storeId })
        assertEquals(StorePrices(12, 18999, 18999, 18999, LocalDate.of(2026, 11, 3)), l.stores[1])
        assertEquals(21999L, chains[1].lastMinor)
    }

    @Test
    fun `trend compares with previous purchase in the same chain`() {
        val points = listOf(point(lidl, 11, 1, 19999), point(maxi, 21, 3, 15000), point(lidl, 11, 5, 20999))
        assertEquals(5, Prices.trendPercent(points))
        assertNull(Prices.trendPercent(listOf(point(lidl, 11, 1, 19999))))
        assertEquals(0, Prices.trendPercent(listOf(point(lidl, 11, 1, 19999), point(lidl, 11, 2, 19999))))
        assertNull(Prices.trendPercent(emptyList()))
    }

    @Test
    fun `overview sorted by last purchase and skips products without points`() {
        val sauce = Product(10, "Bolonjeze sos", ItemUnit.PCS)
        val chicken = Product(20, "Pileći file", ItemUnit.KG)
        val milk = Product(30, "Mleko", ItemUnit.L)
        val points = listOf(
            point(lidl, 11, 1, 19999, product = 10),
            point(lidl, 11, 4, 77999, product = 20),
            point(lidl, 11, 3, 20999, product = 10),
        )
        val overview = Prices.overview(listOf(sauce, chicken, milk), points)
        assertEquals(listOf(chicken, sauce), overview.map { it.product })
        assertEquals(20999L, overview[1].last.unitPriceMinor)
        assertEquals(5, overview[1].trendPercent)
        assertNull(overview[0].trendPercent)
    }
}

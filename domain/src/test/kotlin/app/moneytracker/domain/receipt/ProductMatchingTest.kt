package app.moneytracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductMatchingTest {

    @Test
    fun `similar finds same unit products from other chains`() {
        val target = Product(1, "Bolonjeze sos", ItemUnit.PCS)
        val barilla = Product(2, "SOS BOLONJEZE Barilla", ItemUnit.PCS)
        val lidlTwin = Product(3, "Bolonjeze sos", ItemUnit.PCS)
        val milk = Product(4, "Mleko", ItemUnit.PCS)
        val weighed = Product(5, "Sos bolonjeze", ItemUnit.KG)
        val oneWord = Product(6, "Sos", ItemUnit.PCS)
        val candidates = listOf(
            ProductCandidate(target, setOf(1)),
            ProductCandidate(oneWord, setOf(3)),
            ProductCandidate(barilla, setOf(2)),
            ProductCandidate(lidlTwin, setOf(1)),
            ProductCandidate(milk, setOf(2)),
            ProductCandidate(weighed, setOf(3)),
        )
        assertEquals(listOf(barilla, oneWord), ProductMatching.similar(target, setOf(1), candidates))
    }

    @Test
    fun `similar respects limit`() {
        val target = Product(1, "Sos", ItemUnit.PCS)
        val many = (2L..10L).map { ProductCandidate(Product(it, "Sos $it", ItemUnit.PCS), setOf(2)) }
        assertEquals(3, ProductMatching.similar(target, setOf(1), many, limit = 3).size)
    }
}

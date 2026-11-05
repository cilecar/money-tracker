package app.moneytracker.ui.prices

import app.moneytracker.domain.receipt.Chain
import app.moneytracker.domain.receipt.ItemUnit
import app.moneytracker.domain.receipt.PricePoint
import app.moneytracker.domain.receipt.Product
import app.moneytracker.domain.receipt.ProductOverview
import app.moneytracker.domain.receipt.Receipt
import app.moneytracker.domain.receipt.ReceiptRow
import app.moneytracker.domain.receipt.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PricesUiStateTest {
    private val lidl = Chain(1, "106884584", "LIDL SRBIJA KD", "Lidl", null)
    private val at = Instant.parse("2026-11-05T14:28:04Z")

    private fun overview(id: Long, name: String, unit: ItemUnit, price: Long, trend: Int?) = ProductOverview(
        Product(id, name, unit),
        PricePoint(id, id, 1, 1, 1, null, at, LocalDate.of(2026, 11, 5), 1000, price, price),
        trend,
    )

    private fun receipt(id: Long, status: ReceiptStatus, expenseId: Long?) =
        ReceiptRow(Receipt(id, "N$id", "u", 100, at, null, expenseId, status, null, null, at), null, null)

    @Test
    fun `search is case insensitive and keeps order`() {
        val items = listOf(overview(1, "Bolonjeze sos", ItemUnit.PCS, 19999, 5), overview(2, "Pileći file", ItemUnit.KG, 77999, null))
        val all = buildPricesState(items, mapOf(1L to lidl), emptyList(), "")
        assertEquals(listOf(1L, 2L), all.items.map { it.productId })
        assertEquals(PriceRow(1, "Bolonjeze sos", 19999, ItemUnit.PCS, "Lidl", LocalDate.of(2026, 11, 5), 5), all.items[0])
        assertEquals(listOf(2L), buildPricesState(items, mapOf(1L to lidl), emptyList(), "  FILE ").items.map { it.productId })
    }

    @Test
    fun `banner counts pending and loaded receipts without expense`() {
        val rows = listOf(
            receipt(1, ReceiptStatus.PENDING, null),
            receipt(2, ReceiptStatus.PENDING, 5),
            receipt(3, ReceiptStatus.LOADED, null),
            receipt(4, ReceiptStatus.LOADED, 6),
            receipt(5, ReceiptStatus.FAILED, null),
        )
        val s = buildPricesState(emptyList(), emptyMap(), rows, "")
        assertEquals(2, s.pendingCount)
        assertEquals(1, s.unattachedCount)
    }
}

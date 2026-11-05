package app.moneytracker.ui.prices

import app.moneytracker.domain.receipt.Receipt
import app.moneytracker.domain.receipt.ReceiptRow
import app.moneytracker.domain.receipt.ReceiptStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ReceiptsFilterTest {
    private fun row(id: Long, status: ReceiptStatus, expenseId: Long?, at: String) = ReceiptRow(
        Receipt(id, "N$id", "u", 100, Instant.parse(at), null, expenseId, status, null, null, Instant.parse(at)), null, null,
    )

    private val rows = listOf(
        row(1, ReceiptStatus.PENDING, null, "2026-11-01T10:00:00Z"),
        row(2, ReceiptStatus.FAILED, null, "2026-11-03T10:00:00Z"),
        row(3, ReceiptStatus.LOADED, null, "2026-11-02T10:00:00Z"),
        row(4, ReceiptStatus.LOADED, 9, "2026-11-04T10:00:00Z"),
    )

    @Test
    fun `each filter returns its receipts newest first`() {
        assertEquals(listOf(4L, 2L, 3L, 1L), filterRows(rows, ReceiptFilter.ALL).map { it.receipt.id })
        assertEquals(listOf(1L), filterRows(rows, ReceiptFilter.PENDING).map { it.receipt.id })
        assertEquals(listOf(2L), filterRows(rows, ReceiptFilter.FAILED).map { it.receipt.id })
        assertEquals(listOf(3L), filterRows(rows, ReceiptFilter.UNATTACHED).map { it.receipt.id })
    }

    @Test
    fun `unknown filter name falls back to all`() {
        assertEquals(ReceiptFilter.ALL, ReceiptFilter.of("nonsense"))
        assertEquals(ReceiptFilter.PENDING, ReceiptFilter.of("PENDING"))
    }
}

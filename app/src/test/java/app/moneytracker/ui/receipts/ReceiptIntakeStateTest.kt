package app.moneytracker.ui.receipts

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.usecase.ReceiptCandidate
import app.moneytracker.domain.usecase.ReceiptCandidates
import app.moneytracker.ui.navigation.ExpenseEditRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ReceiptIntakeStateTest {
    private val total = Money(119796, CurrencyCode.RSD)
    private val at = Instant.parse("2026-11-05T14:28:04Z")

    private fun expense(id: Long) = Expense.create(total, 1, 1, at, ZoneOffset.UTC, null, at).copy(id = id)
    private fun capture(id: Long) = CapturedPurchase(
        id = id, source = "wallet", amount = total, merchant = "LIDL", merchantKey = "lidl", cardSuffix = null,
        occurredAt = at, notificationKey = "k", status = CaptureStatus.PENDING, createdAt = at,
    )

    @Test
    fun `no candidates opens new expense from receipt`() {
        val (state, event) = decide(7, total, ReceiptCandidates(emptyList(), emptyList()))
        assertNull(state)
        assertEquals(IntakeEvent.OpenEditor(ExpenseEditRoute(receiptId = 7)), event)
    }

    @Test
    fun `no matching but same day expenses still opens new expense`() {
        val (state, event) = decide(7, total, ReceiptCandidates(emptyList(), listOf(expense(1))))
        assertNull(state)
        assertEquals(IntakeEvent.OpenEditor(ExpenseEditRoute(receiptId = 7)), event)
    }

    @Test
    fun `one expense candidate asks`() {
        val c = ReceiptCandidate.OfExpense(expense(1))
        val (state, event) = decide(7, total, ReceiptCandidates(listOf(c), listOf(expense(1))))
        assertNull(event)
        assertEquals(IntakeUiState.Choose(7, total, listOf(c), listOf(expense(1)), showSameDay = false), state)
    }

    @Test
    fun `two candidates ask`() {
        val list = listOf(ReceiptCandidate.OfExpense(expense(1)), ReceiptCandidate.OfExpense(expense(2)))
        assertEquals(2, (decide(7, total, ReceiptCandidates(list, emptyList())).first as IntakeUiState.Choose).matching.size)
    }

    @Test
    fun `pending capture candidate asks`() {
        val c = ReceiptCandidate.OfCapture(capture(3))
        val state = decide(7, total, ReceiptCandidates(listOf(c), emptyList())).first as IntakeUiState.Choose
        assertEquals(listOf(c), state.matching)
    }
}

package app.moneytracker.ui.expenses

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.usecase.ReceiptPrefill
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.expenses.edit.ExpenseForm
import app.moneytracker.ui.expenses.edit.withReceipt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Скан чека в новом расходе заполняет форму из чека — запись с телефона: сумма оставалась 0. */
class ReceiptPrefillFormTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val prefill = ReceiptPrefill(Money(119796, CurrencyCode.RSD), Instant.parse("2026-11-05T14:28:04.875Z"), "Lidl", 3)
    private val empty = ExpenseForm(loaded = true, currency = CurrencyCode.EUR, accountId = 1, dateTime = LocalDateTime.of(2026, 11, 5, 22, 34))

    @Test
    fun `empty new expense takes amount date comment and category from receipt`() {
        val f = empty.withReceipt(7, prefill, zone)
        assertEquals(AmountInput.from(Money(119796, CurrencyCode.RSD)), f.amount)
        assertEquals(CurrencyCode.RSD, f.currency)
        assertEquals(LocalDateTime.of(2026, 11, 5, 15, 28, 4, 875_000_000), f.dateTime)
        assertEquals("Lidl", f.comment)
        assertEquals(3L, f.categoryId)
        assertEquals(7L, f.pendingReceiptId)
        assertEquals(1L, f.accountId)
    }

    @Test
    fun `typed comment and chosen category are kept`() {
        val f = empty.copy(comment = "к ужину", categoryId = 5).withReceipt(7, prefill, zone)
        assertEquals("к ужину", f.comment)
        assertEquals(5L, f.categoryId)
        assertEquals(AmountInput.from(Money(119796, CurrencyCode.RSD)), f.amount)
    }

    @Test
    fun `pending receipt without chain keeps empty comment and category`() {
        val f = empty.withReceipt(7, prefill.copy(comment = null, categoryId = null), zone)
        assertEquals("", f.comment)
        assertEquals(null, f.categoryId)
    }
}

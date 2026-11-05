package app.moneytracker.ui.expenses

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.expenses.edit.ExpenseEditUiState
import app.moneytracker.ui.expenses.edit.ExpenseForm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Ревью: пока чек загружается, сохранять нельзя — иначе расход сохранится без чека, а чек потеряется. */
class ReceiptScanSaveTest {
    private fun state(scanning: Boolean) = ExpenseEditUiState(
        form = ExpenseForm(
            loaded = true, amount = AmountInput.from(Money(10000, CurrencyCode.RSD)), categoryId = 1, accountId = 1,
            dateTime = LocalDateTime.of(2026, 11, 5, 15, 0), scanningReceipt = scanning,
        ),
        isNew = true, conversions = emptyList(), ratesDate = null, categories = emptyList(), quickCategories = emptyList(),
        selectedCategory = null, accounts = emptyList(), tagSuggestions = emptyList(),
    )

    @Test
    fun `cannot save while receipt is loading`() {
        assertTrue(state(scanning = false).canSave)
        assertFalse(state(scanning = true).canSave)
    }
}

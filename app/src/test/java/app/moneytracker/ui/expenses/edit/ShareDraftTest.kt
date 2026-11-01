package app.moneytracker.ui.expenses.edit

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.ShareInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareDraftTest {
    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

    @Test
    fun `parses amounts with comma, dot and spaces`() {
        assertEquals(rsd(100000), parseShareAmount("1000", CurrencyCode.RSD))
        assertEquals(rsd(100050), parseShareAmount("1 000,5", CurrencyCode.RSD))
        assertEquals(rsd(33333), parseShareAmount("333.33", CurrencyCode.RSD))
        assertNull(parseShareAmount("", CurrencyCode.RSD))
        assertNull(parseShareAmount("abc", CurrencyCode.RSD))
        // Три знака после запятой — опечатка, а не округление.
        assertNull(parseShareAmount("1.005", CurrencyCode.RSD))
    }

    @Test
    fun `drafts become share inputs only when every amount parses`() {
        val drafts = listOf(ShareDraft(1, "1000"), ShareDraft(2, "500,5", settled = true))
        assertEquals(listOf(ShareInput(1, rsd(100000)), ShareInput(2, rsd(50050))), drafts.toInputs(CurrencyCode.RSD))
        assertNull(listOf(ShareDraft(1, "")).toInputs(CurrencyCode.RSD))
    }

    @Test
    fun `money becomes editable text without trailing zeros`() {
        assertEquals("1000", rsd(100000).toDraftText())
        assertEquals("333.33", rsd(33333).toDraftText())
        assertEquals("0.5", rsd(50).toDraftText())
    }
}

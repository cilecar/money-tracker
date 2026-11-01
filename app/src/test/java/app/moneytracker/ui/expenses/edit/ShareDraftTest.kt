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

    @Test
    fun `equal split leaves settled shares and divides the rest`() {
        // 3000, А уже вернул 2500: остаток 500 — на меня и Б.
        val drafts = listOf(ShareDraft(1, "2500", settled = true), ShareDraft(2, ""))
        assertEquals(listOf(ShareDraft(1, "2500", settled = true), ShareDraft(2, "250")), drafts.splitEqually(rsd(300000)))
        // Без погашенных — как раньше: 1000 на троих.
        assertEquals(listOf("333.33", "333.33"), listOf(ShareDraft(1, ""), ShareDraft(2, "")).splitEqually(rsd(100000)).map { it.amountText })
    }

    private fun form(currency: CurrencyCode, shares: List<ShareDraft>) =
        ExpenseForm(currency = currency, dateTime = java.time.LocalDateTime.of(2026, 10, 12, 19, 0), shares = shares)

    @Test
    fun `currency change stashes shares and returning restores them`() {
        val split = listOf(ShareDraft(1, "1000"), ShareDraft(2, "1000"))
        val eur = form(CurrencyCode.RSD, split).withCurrency(CurrencyCode.EUR)
        assertEquals(emptyList<ShareDraft>(), eur.shares)
        assertEquals(true, eur.sharesChanged)

        // Вернули RSD (кнопкой «вернуть валюту» или выбором другого счёта) — доли на месте.
        val back = eur.withCurrency(CurrencyCode.RSD)
        assertEquals(split, back.shares)
        assertEquals(CurrencyCode.RSD, back.currency)
    }

    @Test
    fun `shares entered in the new currency replace the stash`() {
        val eur = form(CurrencyCode.RSD, listOf(ShareDraft(1, "1000"))).withCurrency(CurrencyCode.EUR)
            .copy(shares = listOf(ShareDraft(3, "5")))
        val rsd = eur.withCurrency(CurrencyCode.RSD)
        // Долей в RSD больше нет: сохранены последние введённые — в EUR.
        assertEquals(emptyList<ShareDraft>(), rsd.shares)
        assertEquals(listOf(ShareDraft(3, "5")), rsd.withCurrency(CurrencyCode.EUR).shares)
    }
}

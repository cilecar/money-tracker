package app.moneytracker.domain.capture

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class DeduplicationTest {
    private val t0 = Instant.parse("2026-10-15T16:59:31Z")
    private val rates = RateSnapshot(BigDecimal("117.2"), BigDecimal("95"), LocalDate.of(2026, 10, 15))

    private fun parsed(amount: Money, merchant: String = "Maxi", at: Instant = t0, key: String = "k-new", source: String = "wallet") =
        ParsedPurchase(source, amount, merchant, "1234", at, key)

    private fun captured(amount: Money, merchant: String = "Maxi", at: Instant = t0, key: String = "k-old", id: Long = 1) =
        CapturedPurchase.from(parsed(amount, merchant, at, key), t0).copy(id = id)

    private val rsd = Money(1250_00, CurrencyCode.RSD)

    @Test
    fun `repeated push with the same key is a duplicate even with other text`() {
        val old = captured(rsd, key = "same")
        assertEquals(old, PurchaseDeduplicator.findDuplicate(parsed(Money(1_00, CurrencyCode.RSD), "Other", t0.plusSeconds(3600), key = "same"), listOf(old), rates))
    }

    @Test
    fun `same amount and merchant within five minutes`() {
        val old = captured(rsd)
        assertEquals(old, PurchaseDeduplicator.findDuplicate(parsed(rsd, at = t0.plusSeconds(300)), listOf(old), rates))
        assertEquals(old, PurchaseDeduplicator.findDuplicate(parsed(rsd, at = t0.minusSeconds(300)), listOf(old), rates))
        assertNull(PurchaseDeduplicator.findDuplicate(parsed(rsd, at = t0.plusSeconds(301)), listOf(old), rates))
    }

    @Test
    fun `merchant names are compared loosely but not by short fragments`() {
        val old = captured(rsd, merchant = "MAXI 123 Beograd")
        assertEquals(old, PurchaseDeduplicator.findDuplicate(parsed(rsd, merchant = "Maxi"), listOf(old), rates))
        assertNull(PurchaseDeduplicator.findDuplicate(parsed(rsd, merchant = "Lidl"), listOf(old), rates))
        assertFalse(Merchants.same("ma", "maxi"))
        assertTrue(Merchants.same("nicefoods", "nicefoodsdoo"))
    }

    @Test
    fun `different amount is not a duplicate`() {
        assertNull(PurchaseDeduplicator.findDuplicate(parsed(Money(1250_01, CurrencyCode.RSD)), listOf(captured(rsd)), rates))
    }

    /** Банк показывает EUR, Wallet — RSD: сравниваем по курсу с допуском 3%, курс банка ≠ курс API. */
    @Test
    fun `cross currency within tolerance`() {
        val walletRsd = captured(Money(1172_00, CurrencyCode.RSD)) // 10 € по API
        assertEquals(walletRsd, PurchaseDeduplicator.findDuplicate(parsed(Money(10_25, CurrencyCode.EUR)), listOf(walletRsd), rates)) // +2.5%
        assertNull(PurchaseDeduplicator.findDuplicate(parsed(Money(10_40, CurrencyCode.EUR)), listOf(walletRsd), rates)) // +4%
        // Без курсов разные валюты не сравнить — лучше показать две записи, чем потерять покупку.
        assertNull(PurchaseDeduplicator.findDuplicate(parsed(Money(10_00, CurrencyCode.EUR)), listOf(walletRsd), null))
    }
}

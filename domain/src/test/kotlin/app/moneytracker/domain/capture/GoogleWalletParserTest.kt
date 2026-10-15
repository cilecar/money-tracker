package app.moneytracker.domain.capture

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GoogleWalletParserTest {
    private val parser = GoogleWalletParser()
    private val at = Instant.parse("2026-10-15T16:59:31.121Z")

    private fun wallet(title: String?, text: String?, bigText: String? = null) =
        RawNotification(GoogleWalletParser.PACKAGE, "0|wallet|1001", title, text, bigText, null, at)

    /** Реальное уведомление с телефона (15.10.2026, язык en-US). */
    @Test
    fun `real sample - BAKERY RSD1,600 with Bybit Mastercard`() {
        val p = parser.parse(wallet("BAKERY", "RSD1,600 with Bybit Mastercard ••1234"))!!
        assertEquals(Money(1600_00, CurrencyCode.RSD), p.amount)
        assertEquals("BAKERY", p.merchant)
        assertEquals("1234", p.cardSuffix)
        assertEquals(at, p.occurredAt)
        assertEquals("0|wallet|1001", p.notificationKey)
    }

    /** Версия для экрана блокировки — без суммы, это не покупка. */
    @Test
    fun `lock screen version is ignored`() {
        assertNull(parser.parse(wallet("Purchase complete", "View the details")))
    }

    @Test
    fun `other locale formats`() {
        fun amount(text: String) = parser.parse(wallet("Maxi", text))?.amount
        assertEquals(Money(1250_50, CurrencyCode.RSD), amount("RSD1,250.50 with Bybit Mastercard ••1234"))
        assertEquals(Money(1600_00, CurrencyCode.RSD), amount("1.600 RSD · Bybit Mastercard ••1234"))
        assertEquals(Money(1600_00, CurrencyCode.RSD), amount("RSD 1.600,00 karticom ••1234"))
        assertEquals(Money(12_50, CurrencyCode.EUR), amount("€12.50 with Bybit Mastercard ••1234"))
        assertEquals(Money(12_50, CurrencyCode.EUR), amount("12,50 € с картой ••1234"))
        assertEquals(Money(1250_00, CurrencyCode.RUB), amount("1 250,00 ₽ с картой ••1234"))
        assertEquals(Money(990_00, CurrencyCode.RSD), amount("990 дин. картицом ••1234"))
    }

    @Test
    fun `unsupported currency and missing parts give null`() {
        assertNull(parser.parse(wallet("Steam", "\$4.99 with Bybit Mastercard ••1234")))
        assertNull(parser.parse(wallet(null, "RSD1,600 with Bybit Mastercard ••1234")))
        assertNull(parser.parse(wallet("  ", "RSD1,600 with Bybit Mastercard ••1234")))
        assertNull(parser.parse(wallet("BAKERY", null)))
    }

    @Test
    fun `card suffix is optional, amount may come from big text`() {
        val p = parser.parse(wallet("Maxi", "Purchase", bigText = "RSD350 with Visa"))!!
        assertEquals(Money(350_00, CurrencyCode.RSD), p.amount)
        assertNull(p.cardSuffix)
    }
}

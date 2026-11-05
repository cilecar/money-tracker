package app.moneytracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.util.Base64

class FiscalLinkParserTest {

    private fun ok(text: String): FiscalLink {
        val r = FiscalLinkParser.parse(text)
        assertTrue("expected Ok, got $r", r is FiscalLinkResult.Ok)
        return (r as FiscalLinkResult.Ok).link
    }

    /** vl из LIDL_URL с изменённым байтом, закодированный обратно. */
    private fun mutated(index: Int, value: Int): String {
        val raw = URLDecoder.decode(LIDL_URL.substringAfter("vl="), Charsets.UTF_8)
        val bytes = Base64.getDecoder().decode(raw)
        bytes[index] = value.toByte()
        val vl = URLEncoder.encode(Base64.getEncoder().encodeToString(bytes), Charsets.UTF_8)
        return "https://suf.purs.gov.rs/v/?vl=$vl"
    }

    /** Реальный чек Lidl пользователя (05.11.2026). */
    @Test
    fun `parses real Lidl link`() {
        val link = ok(LIDL_URL)
        assertEquals("WYBQ8YRF-WYBQ8YRF-360050", link.invoiceNumber)
        assertEquals(119796L, link.totalMinor)
        assertEquals(Instant.parse("2026-11-05T14:28:04.875Z"), link.issuedAt)
        assertEquals(LIDL_URL, link.url)
    }

    @Test
    fun `raw plus and percent-encoded plus give the same result`() {
        val a = ok(LIDL_URL)
        val b = ok(LIDL_URL.replace("%2B", "+"))
        assertEquals(a.invoiceNumber, b.invoiceNumber)
        assertEquals(a.totalMinor, b.totalMinor)
        assertEquals(a.issuedAt, b.issuedAt)
    }

    @Test
    fun `already decoded link with spaces instead of plus`() {
        val decoded = URLDecoder.decode(LIDL_URL, Charsets.UTF_8)
        val link = ok("$decoded ")
        assertEquals("WYBQ8YRF-WYBQ8YRF-360050", link.invoiceNumber)
        assertEquals(119796L, link.totalMinor)
    }

    @Test
    fun `finds url inside shared text`() {
        assertEquals(LIDL_URL, FiscalLinkParser.findUrl("Račun: $LIDL_URL hvala"))
        assertNull(FiscalLinkParser.findUrl("https://example.com"))
    }

    @Test
    fun `foreign host is NotFiscal`() {
        val foreign = LIDL_URL.replace("https://suf.purs.gov.rs", "https://evil.example")
        assertEquals(FiscalLinkResult.NotFiscal, FiscalLinkParser.parse(foreign))
    }

    @Test
    fun `garbage vl is NotFiscal`() {
        assertEquals(FiscalLinkResult.NotFiscal, FiscalLinkParser.parse("https://suf.purs.gov.rs/v/?vl=abc"))
        assertEquals(FiscalLinkResult.NotFiscal, FiscalLinkParser.parse("https://suf.purs.gov.rs/v/"))
        assertEquals(FiscalLinkResult.NotFiscal, FiscalLinkParser.parse("просто текст"))
    }

    @Test
    fun `refund advance and unknown version are Unsupported`() {
        assertEquals(FiscalLinkResult.Unsupported, FiscalLinkParser.parse(mutated(42, 1)))
        assertEquals(FiscalLinkResult.Unsupported, FiscalLinkParser.parse(mutated(41, 1)))
        assertEquals(FiscalLinkResult.Unsupported, FiscalLinkParser.parse(mutated(0, 2)))
    }

    companion object {
        const val LIDL_URL =
            "https://suf.purs.gov.rs/v/?vl=A1dZQlE4WVJGV1lCUThZUkZyfgUAD0AFAFDLtgAAAAAAAAABoawcGIsAAAAyBb1D3f4wiKjpDI8R9JJVVIxFeKg4whxffdii7U0PHrLX%2Bnm4eqDML3Mk2onCIN9dV9FJU%2FMQ6jNyxxpKDtLHK7RTW0ZefxRJYVBcy48UMUHzOIhYKFTakT4cONO3omNARyVIzBxW%2BaGFpOIT%2FX1sqBipsWn3BToDNZNQqYY%2FqECrR2SGH9E7%2FTMx%2B5XVsNOBqJO4MJDfLaMuTlqQyjxgknj0OVBiQp37uglgNDLiF3Qc3mFDHJP6fYeyReJFIjD6z%2FrU3xqtv1x%2F5iZgo2lC80ZqF5AaYQp%2BM2y6bC0zCqZiXExIA3rMsrZAEzNWgKqIC%2Fss0Ber%2BEn6ZHORIYv%2BvLYF%2B7xKx8Edb%2BdGGE3GegDerIEnO0ZfhmmrQ0a1ubDtvTEhCvhk10LHxyVzpnjT3wKK7CyIL5ccM%2FCS8k6KhYeRUqGDhdAr8BPNw98EcOrlPpiBH8guzLfq%2FzMNJERBQJamV%2FCeDWweZzRdQYBpgaGInzyC7i05v0Au9JWYK1hKYt%2F4gGuUpOxTX7KmXbn0lFABh7SMPnTFdZrGxcoqLzE10ERKZEOT3U5KOXbU%2FD7G5Kb9MO65itAk%2FQav5mHP3GkOzJaFD0xcKtQlopPTi%2Fffu1kv%2FDPPPLUuPjRnD4ueIO6iJCX1ufhl9k%2FKmTDYNXT3IEjfU0My3hgWOcJkJOa0rnVaCVfD5aszZlxrkjg%3D"
    }
}

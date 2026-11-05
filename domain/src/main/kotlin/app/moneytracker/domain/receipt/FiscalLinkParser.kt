package app.moneytracker.domain.receipt

import java.math.BigInteger
import java.math.RoundingMode
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Base64

/**
 * Ссылка из QR сербского фискального чека: `https://suf.purs.gov.rs/v/?vl=<base64>`.
 * В vl без сети читаются номер чека, сумма и время — этого хватает, чтобы найти или создать расход.
 */
object FiscalLinkParser {
    private const val PREFIX = "https://suf.purs.gov.rs/v/?vl="
    private val URL = Regex("""https://suf\.purs\.gov\.rs/v/\?vl=[A-Za-z0-9%+/=_\-]+""")
    private const val MIN_LENGTH = 43

    /** Первая ссылка налоговой в тексте («Поделиться» может прислать её вместе с другими словами). */
    fun findUrl(text: String): String? = URL.find(text)?.value

    fun parse(text: String): FiscalLinkResult {
        // Уже декодированная ссылка (пробелы вместо «+») принимается, только если весь текст — она сама:
        // иначе пробел не отличить от слов после ссылки.
        val candidates = listOfNotNull(text.trim().takeIf { it.startsWith(PREFIX) }, findUrl(text))
        val base64 = candidates.firstNotNullOfOrNull { vlBase64(it) } ?: return FiscalLinkResult.NotFiscal
        val bytes = Base64.getDecoder().decode(base64)
        if (bytes.size < MIN_LENGTH) return FiscalLinkResult.NotFiscal
        // Каноничная ссылка: одинаковая для «+» и «%2B», ею потом загружается чек.
        val url = PREFIX + URLEncoder.encode(base64, Charsets.UTF_8)

        val le = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val version = bytes[0].toInt()
        val requestedBy = String(bytes, 1, 8, Charsets.US_ASCII)
        val signedBy = String(bytes, 9, 8, Charsets.US_ASCII)
        val totalCounter = le.getInt(17).toUInt()
        // 1/10000 динара, беззнаковое → пары.
        val amount = BigInteger(java.lang.Long.toUnsignedString(le.getLong(25)))
            .toBigDecimal().movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val issuedAt = Instant.ofEpochMilli(ByteBuffer.wrap(bytes, 33, 8).order(ByteOrder.BIG_ENDIAN).long)
        val invoiceType = bytes[41].toInt()
        val transactionType = bytes[42].toInt()

        if (version != 3 || invoiceType != 0 || transactionType != 0) return FiscalLinkResult.Unsupported
        if (!requestedBy.all { it.isLetterOrDigit() } || !signedBy.all { it.isLetterOrDigit() }) {
            return FiscalLinkResult.NotFiscal
        }
        return FiscalLinkResult.Ok(FiscalLink(url, "$requestedBy-$signedBy-$totalCounter", amount, issuedAt))
    }

    /**
     * Сканеры отдают vl то с `%2B`, то с сырым `+`, а уже декодированный текст — с пробелами вместо `+`.
     * Обычный URL-декодер превратил бы `+` в пробел, поэтому `+` сохраняем, а пробелы возвращаем в `+`.
     */
    private fun vlBase64(url: String): String? = try {
        val raw = url.substringAfter("vl=").substringBefore('&')
        val base64 = URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8).replace(' ', '+')
        base64.padEnd((base64.length + 3) / 4 * 4, '=').also { Base64.getDecoder().decode(it) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

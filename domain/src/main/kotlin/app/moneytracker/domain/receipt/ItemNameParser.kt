package app.moneytracker.domain.receipt

import java.util.Locale

/**
 * Название позиции → код артикула, имя и единица.
 * Lidl пишет `Название/ЕД/артикул` (`Pileći file/KG/0238588`); у остальных сетей код — нормализованное название.
 * Новые форматы добавляются по реальным образцам, как у парсера Wallet.
 */
object ItemNameParser {
    private val TAX_LABEL = Regex("""\s*\([^)]*\)\s*$""")
    private val SPACES = Regex("""\s+""")
    private val UNITS = mapOf("KG" to ItemUnit.KG, "KOM" to ItemUnit.PCS, "L" to ItemUnit.L, "LIT" to ItemUnit.L)

    fun parse(raw: String): ParsedItemName {
        val trimmed = stripTaxLabel(raw)
        val parts = trimmed.split('/')
        if (parts.size >= 3) {
            val code = parts.last().trim()
            val unit = UNITS[parts[parts.size - 2].trim().uppercase(Locale.ROOT)]
            if (unit != null && code.isNotEmpty() && code.all { it.isDigit() }) {
                return ParsedItemName(code, parts.dropLast(2).joinToString("/").trim(), unit)
            }
        }
        val tokens = trimmed.split(' ', '/').map { it.uppercase(Locale.ROOT) }
        val unit = if ("KG" in tokens) ItemUnit.KG else ItemUnit.PCS
        return ParsedItemName(normalize(trimmed), trimmed, unit)
    }

    /** Метка налоговой ставки в конце (`(Ђ)`) к названию не относится. */
    fun stripTaxLabel(raw: String): String = raw.trim().replace(TAX_LABEL, "").trim()

    fun normalize(text: String): String = text.lowercase(Locale.ROOT).replace(SPACES, " ").trim()
}

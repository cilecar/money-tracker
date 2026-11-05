package app.moneytracker.domain.receipt

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Позиции из текста чека (`journal`) — запасной путь, если сервер не отдал их JSON-ом.
 * Блок между «Артикли» и «Укупан износ»: строка (или строки) названия, затем строка «цена кол. сумма».
 */
object JournalParser {
    private val NUMBERS = Regex("""^\s*(-?[\d.]+,\d{2})\s+(-?[\d.]*,?\d+)\s+(-?[\d.]+,\d{2})\s*$""")

    fun parseItems(journal: String): List<FetchedItem>? {
        val lines = journal.lines().map { it.trimEnd('\r') }
        val start = lines.indexOfFirst { it.trim() == "Артикли" }
        if (start < 0) return null
        val header = (start + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("Назив") } ?: return null

        val items = mutableListOf<FetchedItem>()
        val name = mutableListOf<String>()
        for (line in lines.drop(header + 1)) {
            if (line.startsWith("---") || line.trimStart().startsWith("Укупан износ")) break
            val m = NUMBERS.matchEntire(line)
            if (m == null) {
                if (line.isNotBlank()) name += line.trim()
                continue
            }
            if (name.isEmpty()) continue
            val (price, quantity, total) = m.destructured
            items += FetchedItem(
                rawName = ItemNameParser.stripTaxLabel(name.joinToString(" ")),
                quantityMilli = number(quantity, 3),
                unitPriceMinor = number(price, 2),
                totalMinor = number(total, 2),
                vatRate = null,
            )
            name.clear()
        }
        return items.ifEmpty { null }
    }

    /** «1.299,99» → 129999 (для [scale] 2). */
    private fun number(text: String, scale: Int): Long =
        BigDecimal(text.replace(".", "").replace(',', '.')).movePointRight(scale).setScale(0, RoundingMode.HALF_UP).longValueExact()
}

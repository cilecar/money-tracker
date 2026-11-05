package app.moneytracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalParserTest {

    private fun journal(vararg itemLines: String) = (
        listOf(
            "============ ФИСКАЛНИ РАЧУН ============",
            "-------------ПРОМЕТ ПРОДАЈА-------------",
            "Артикли",
            "========================================",
            "Назив   Цена         Кол.         Укупно",
        ) + itemLines + listOf(
            "----------------------------------------",
            "Укупан износ:                   1.197,96",
            "Готовина:                       1.200,00",
        )
        ).joinToString("\r\n")

    @Test
    fun `parses lidl journal`() {
        val items = JournalParser.parseItems(LIDL_JOURNAL)!!
        assertEquals(4, items.size)
        assertEquals(FetchedItem("Pileći file/KG/0238588", 1000, 77999, 77999, null), items[0])
        assertEquals("Bolonjeze sos/KOM/0200689", items[1].rawName)
        assertEquals(1799L, items[3].totalMinor)
    }

    @Test
    fun `fractional weight and thousands`() {
        val items = JournalParser.parseItems(journal("Sir/KG/1 (Е)", "     1.299,99      0,534        694,19"))!!
        assertEquals(534L, items.single().quantityMilli)
        assertEquals(129999L, items.single().unitPriceMinor)
        assertEquals(69419L, items.single().totalMinor)
    }

    @Test
    fun `wrapped long name`() {
        val items = JournalParser.parseItems(
            journal("Vrlo dugačak naziv proizvoda koji se", "prelama/KOM/123 (Ђ)", "       99,99          2          199,98"),
        )!!
        assertEquals("Vrlo dugačak naziv proizvoda koji se prelama/KOM/123", items.single().rawName)
        assertEquals(2000L, items.single().quantityMilli)
    }

    @Test
    fun `negative discount line`() {
        val items = JournalParser.parseItems(journal("Popust (Ђ)", "       -20,00          1          -20,00"))!!
        assertEquals(-2000L, items.single().totalMinor)
        assertEquals("Popust", items.single().rawName)
    }

    @Test
    fun `no items block`() {
        assertNull(JournalParser.parseItems("garbage"))
    }

    companion object {
        /** Текст реального чека Lidl (05.11.2026) — как его отдаёт сервер, с \r\n. */
        val LIDL_JOURNAL = listOf(
            "============ ФИСКАЛНИ РАЧУН ============",
            "               106884584                ",
            "             LIDL SRBIJA KD             ",
            "      1056845-Prodavnica br. 0123       ",
            "-------------ПРОМЕТ ПРОДАЈА-------------",
            "Артикли",
            "========================================",
            "Назив   Цена         Кол.         Укупно",
            "Pileći file/KG/0238588 (Е)              ",
            "       779,99          1          779,99",
            "Bolonjeze sos/KOM/0200689 (Ђ)           ",
            "       199,99          1          199,99",
            "Bolonjeze sos/KOM/0200689 (Ђ)           ",
            "       199,99          1          199,99",
            "Papirna kesa Mala/KOM/9004925 (Ђ)       ",
            "        17,99          1           17,99",
            "----------------------------------------",
            "Укупан износ:                   1.197,96",
            "Готовина:                       1.200,00",
            "========================================",
        ).joinToString("\r\n")
    }
}

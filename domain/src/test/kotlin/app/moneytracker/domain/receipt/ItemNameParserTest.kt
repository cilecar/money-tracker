package app.moneytracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemNameParserTest {

    @Test
    fun `lidl kg`() {
        assertEquals(ParsedItemName("0238588", "Pileći file", ItemUnit.KG), ItemNameParser.parse("Pileći file/KG/0238588"))
    }

    @Test
    fun `lidl kom`() {
        assertEquals(ParsedItemName("0200689", "Bolonjeze sos", ItemUnit.PCS), ItemNameParser.parse("Bolonjeze sos/KOM/0200689"))
    }

    @Test
    fun `lidl liter`() {
        assertEquals(ItemUnit.L, ItemNameParser.parse("Mleko/L/0100001").unit)
        assertEquals(ItemUnit.L, ItemNameParser.parse("Mleko/LIT/0100001").unit)
    }

    @Test
    fun `tax label stripped`() {
        assertEquals(ParsedItemName("0200689", "Bolonjeze sos", ItemUnit.PCS), ItemNameParser.parse("Bolonjeze sos/KOM/0200689 (Ђ)"))
    }

    @Test
    fun `fallback uses normalized name`() {
        assertEquals(
            ParsedItemName("sos bolonjeze barilla 400g", "SOS  BOLONJEZE Barilla 400G", ItemUnit.PCS),
            ItemNameParser.parse("  SOS  BOLONJEZE Barilla 400G "),
        )
    }

    @Test
    fun `fallback detects kg token`() {
        assertEquals(ItemUnit.KG, ItemNameParser.parse("BANANE KG").unit)
        assertEquals(ItemUnit.PCS, ItemNameParser.parse("KGB sir").unit)
    }
}

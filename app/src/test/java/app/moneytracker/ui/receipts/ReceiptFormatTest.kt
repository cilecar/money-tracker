package app.moneytracker.ui.receipts

import app.moneytracker.domain.receipt.ItemUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ReceiptFormatTest {
    private val ru = Locale.forLanguageTag("ru")
    private val names = UnitNames(kg = "кг", pcs = "шт", l = "л")

    @Test
    fun `formats weight pieces and liters`() {
        assertEquals("0,534 кг", formatQuantity(534, ItemUnit.KG, names, ru))
        assertEquals("1 шт", formatQuantity(1000, ItemUnit.PCS, names, ru))
        assertEquals("2 шт", formatQuantity(2000, ItemUnit.PCS, names, ru))
        assertEquals("1,5 л", formatQuantity(1500, ItemUnit.L, names, ru))
    }

    @Test
    fun `per unit label`() {
        assertEquals("/кг", perUnitLabel(ItemUnit.KG, names))
        assertEquals("/шт", perUnitLabel(ItemUnit.PCS, names))
    }
}

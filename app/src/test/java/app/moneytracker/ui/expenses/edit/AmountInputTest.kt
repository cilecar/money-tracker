package app.moneytracker.ui.expenses.edit

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AmountInputTest {

    private fun type(keys: String): AmountInput = keys.fold(AmountInput()) { acc, c ->
        acc.press(
            when (c) {
                ',' -> KeypadKey.Decimal
                '<' -> KeypadKey.Backspace
                'C' -> KeypadKey.Clear
                else -> KeypadKey.Digit(c.digitToInt())
            },
        )
    }

    @Test
    fun `typing integer`() {
        assertEquals(Money(125000, CurrencyCode.RSD), type("1250").toMoney(CurrencyCode.RSD))
    }

    @Test
    fun `typing decimals is limited to two digits`() {
        val input = type("12,505")
        assertEquals("50", input.fraction)
        assertEquals(Money(1250, CurrencyCode.EUR), input.toMoney(CurrencyCode.EUR))
    }

    @Test
    fun `single fraction digit means tenths`() {
        assertEquals(Money(1250, CurrencyCode.EUR), type("12,5").toMoney(CurrencyCode.EUR))
    }

    @Test
    fun `decimal first gives leading zero`() {
        val input = type(",5")
        assertEquals("0", input.integer)
        assertEquals(Money(50, CurrencyCode.EUR), input.toMoney(CurrencyCode.EUR))
    }

    @Test
    fun `leading zeros are replaced`() {
        assertEquals("7", type("007").integer)
    }

    @Test
    fun `second decimal is ignored`() {
        assertEquals(type("1,2"), type("1,,2"))
    }

    @Test
    fun `backspace removes separator too`() {
        assertEquals(AmountInput("12"), type("12,<"))
        assertEquals(AmountInput("12", "5"), type("12,55<"))
        assertTrue(type("1<").isEmpty)
    }

    @Test
    fun `clear resets`() {
        assertTrue(type("123,4C").isEmpty)
    }

    @Test
    fun `integer part length is capped`() {
        assertEquals(AmountInput.MAX_INTEGER_DIGITS, type("1234567890123").integer.length)
    }

    @Test
    fun `round trip from money`() {
        assertEquals(AmountInput("12", "5"), AmountInput.from(Money(1250, CurrencyCode.EUR)))
        assertEquals(AmountInput("12", "05"), AmountInput.from(Money(1205, CurrencyCode.EUR)))
        assertEquals(AmountInput("1500"), AmountInput.from(Money(150000, CurrencyCode.RSD)))
        assertEquals(Money(1205, CurrencyCode.EUR), AmountInput.from(Money(1205, CurrencyCode.EUR)).toMoney(CurrencyCode.EUR))
    }

    @Test
    fun `display groups thousands per locale`() {
        assertEquals("1 234 567,5", type("1234567,5").display(Locale.forLanguageTag("ru")))
        assertEquals("1,234,567.5", type("1234567,5").display(Locale.US))
        assertEquals("0", AmountInput().display(Locale.US))
    }
}

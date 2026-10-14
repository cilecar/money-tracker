package app.moneytracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

class MoneyTest {

    @Test
    fun `amount exposes exact decimal`() {
        assertEquals(BigDecimal("123.45"), Money(12345, CurrencyCode.EUR).amount)
        assertEquals(BigDecimal("-0.01"), Money(-1, CurrencyCode.RSD).amount)
    }

    @Test
    fun `of rounds half even`() {
        assertEquals(2L, Money.of(BigDecimal("0.025"), CurrencyCode.EUR).minor)
        assertEquals(4L, Money.of(BigDecimal("0.035"), CurrencyCode.EUR).minor)
        assertEquals(3L, Money.of(BigDecimal("0.025"), CurrencyCode.EUR, RoundingMode.HALF_UP).minor)
    }

    @Test
    fun `arithmetic stays in minor units`() {
        val sum = (1..10).fold(Money.zero(CurrencyCode.EUR)) { acc, _ -> acc + Money(10, CurrencyCode.EUR) }
        assertEquals(Money(100, CurrencyCode.EUR), sum) // 0.1 * 10 == 1.00 ровно, в отличие от Double
        assertEquals(Money(-50, CurrencyCode.RUB), Money(50, CurrencyCode.RUB) - Money(100, CurrencyCode.RUB))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `adding different currencies fails`() {
        Money(1, CurrencyCode.EUR) + Money(1, CurrencyCode.RSD)
    }

    @Test(expected = ArithmeticException::class)
    fun `overflow is detected`() {
        Money(Long.MAX_VALUE, CurrencyCode.RSD) + Money(1, CurrencyCode.RSD)
    }

    @Test
    fun `parse accepts common formats`() {
        val c = CurrencyCode.RSD
        assertEquals(123400L, Money.parse("1234", c)?.minor)
        assertEquals(123450L, Money.parse("1234.5", c)?.minor)
        assertEquals(123456L, Money.parse("1234,56", c)?.minor)
        assertEquals(123456L, Money.parse("1 234,56", c)?.minor)
        assertEquals(123456L, Money.parse("1.234,56", c)?.minor)
        assertEquals(123456L, Money.parse("1,234.56", c)?.minor)
        assertEquals(123400L, Money.parse("1.234", c)?.minor)
        assertEquals(100000000L, Money.parse("1.000.000", c)?.minor)
        assertEquals(50L, Money.parse(",5", c)?.minor)
        assertEquals(1250L, Money.parse(" 12,50 ", c)?.minor)
        assertEquals(1200L, Money.parse("12,", c)?.minor)
    }

    @Test
    fun `parse rejects garbage`() {
        val c = CurrencyCode.EUR
        assertNull(Money.parse("", c))
        assertNull(Money.parse("   ", c))
        assertNull(Money.parse("abc", c))
        assertNull(Money.parse("12e3", c))
        assertNull(Money.parse(".", c))
        assertNull(Money.parse("-", c))
    }

    @Test
    fun `parse rejects amounts too large to convert`() {
        val c = CurrencyCode.EUR
        // Было принято, а при конвертации EUR→RSD не влезало в Long: ArithmeticException
        // в RunRecurring при каждом запуске приложения.
        assertNull(Money.parse("1234567890123456", c))
        assertNull(Money.parse("1 000 000 000", c))
        assertEquals(99_999_999_999L, Money.parse("999 999 999,99", c)?.minor)
    }

    @Test
    fun `parse rejects malformed numbers instead of guessing`() {
        val c = CurrencyCode.EUR
        assertNull(Money.parse("12.3456", c)) // было 123456.00
        assertNull(Money.parse("0.125", c)) // было 125.00
        assertNull(Money.parse("1.2.3", c)) // было 123.00
        assertNull(Money.parse("1..5", c)) // было 15.00
        assertNull(Money.parse("1.234.56", c)) // было 123456.00
        assertNull(Money.parse("1,23.45", c))
        assertNull(Money.parse("12,5.5", c))
    }

    @Test
    fun `parse rejects negative and overflowing amounts`() {
        val c = CurrencyCode.EUR
        assertNull(Money.parse("-5", c))
        assertNull(Money.parse("-10.5", c))
        assertNull(Money.parse("99999999999999999999", c)) // не помещается в Long — null, а не исключение
    }

    @Test
    fun `toString is human readable`() {
        assertEquals("1.05 EUR", Money(105, CurrencyCode.EUR).toString())
    }
}

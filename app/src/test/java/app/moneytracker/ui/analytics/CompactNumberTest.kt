package app.moneytracker.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class CompactNumberTest {
    @Test
    fun negativeValuesKeepCompactFormAndNoMinusZero() {
        assertEquals("−4", compactNumber(-4.0))
        assertEquals("−1,2k", compactNumber(-1200.0).replace('.', ','))
        assertEquals("0", compactNumber(-0.4))
        assertEquals("950", compactNumber(950.0))
    }
}

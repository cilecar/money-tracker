package app.moneytracker.ui.receipts

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Ревью: «Открыть на сайте налоговой» не должно вернуться в само приложение (оно перехватывает эти ссылки). */
@RunWith(RobolectricTestRunner::class)
class BrowserIntentTest {
    @Test
    fun `site link goes to a browser, not back to the app`() {
        val url = "https://suf.purs.gov.rs/v/?vl=abc"
        val intent = browserIntent(url)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        val selector = intent.selector!!
        assertEquals(Intent.ACTION_MAIN, selector.action)
        assertTrue(selector.hasCategory(Intent.CATEGORY_APP_BROWSER))
    }
}

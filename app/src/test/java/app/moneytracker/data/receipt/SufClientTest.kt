package app.moneytracker.data.receipt

import app.moneytracker.domain.receipt.FetchResult
import app.moneytracker.domain.receipt.FetchedItem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

/** Robolectric — ради android.util.Log в клиенте. */
@RunWith(RobolectricTestRunner::class)
class SufClientTest {

    private val url = "https://suf.purs.gov.rs/v/?vl=A1dZQlE4WVJGV1lCUThZUkZyfgUAD0AF"

    private val header = """{"invoiceRequest":{"taxId":"106884584","businessName":"LIDL SRBIJA KD","locationName":"1056845-Prodavnica br. 0123","address":"ОБРЕНОВАЧКИ ДРУМ 1   ","city":"БЕОГРАД (ЧУКАРИЦА)","administrativeUnit":"Београд-Чукарица","payments":[{"paymentType":1,"amount":1200.0}]},"invoiceResult":{"totalAmount":1197.96,"transactionTypeCounter":344079,"totalCounter":360050,"invoiceCounterExtension":"ПП","invoiceNumber":"WYBQ8YRF-WYBQ8YRF-360050","signedBy":"WYBQ8YRF","sdcTime":"2026-11-05T14:28:04.875Z"},"journal":${Json.encodeToString(String.serializer(), JOURNAL)},"isValid":true}"""
    private val html = "<html><script>viewModel.InvoiceNumber('WYBQ8YRF-WYBQ8YRF-360050'); viewModel.Token('217e02d4-d2d5-4d25-8ebd-85a5876ef3aa');</script></html>"
    private val specs = """{"success":true,"items":[{"gtin":"","name":"Pileći file/KG/0238588","quantity":0.534,"total":416.51,"unitPrice":779.99,"label":"Е","labelRate":10,"taxBaseAmount":378.65,"vatAmount":37.86},{"gtin":"","name":"Bolonjeze sos/KOM/0200689","quantity":1,"total":199.99,"unitPrice":199.99,"label":"Ђ","labelRate":20,"taxBaseAmount":166.66,"vatAmount":33.33}]}"""

    /** Фейковый сервер: отвечает фикстурами и записывает запросы (с телами). */
    private class FakeServer(
        var header: () -> Response.Builder,
        var html: () -> Response.Builder,
        var specs: () -> Response.Builder,
    ) : Interceptor {
        val requests = mutableListOf<Pair<Request, String?>>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            requests += req to body
            val builder = when {
                req.method == "POST" && req.url.encodedPath == "/specifications" -> specs()
                req.header("Accept") == "application/json" -> header()
                else -> html()
            }
            return builder.request(req).protocol(Protocol.HTTP_1_1).build()
        }
    }

    private fun ok(body: String, type: String = "application/json", cookie: String? = null) =
        Response.Builder().code(200).message("OK").body(body.toResponseBody(type.toMediaType())).apply {
            if (cookie != null) header("Set-Cookie", cookie)
        }

    private fun status(code: Int) = Response.Builder().code(code).message("x").body("".toResponseBody(null))

    private val server = FakeServer(
        header = { ok(header) },
        html = { ok(html, "text/html", cookie = "sid=1; Path=/") },
        specs = { ok(specs) },
    )

    private fun client() = SufClient(OkHttpClient.Builder().addInterceptor(server).build(), Json { ignoreUnknownKeys = true })

    @Test
    fun `fetches header and items`() = runTest {
        val r = (client().fetch(url) as FetchResult.Ok).receipt
        assertEquals("106884584", r.taxId)
        assertEquals("LIDL SRBIJA KD", r.officialName)
        assertEquals("1056845", r.locationCode)
        assertEquals("1056845-Prodavnica br. 0123", r.locationName)
        assertEquals("ОБРЕНОВАЧКИ ДРУМ 1", r.address)
        assertEquals("БЕОГРАД (ЧУКАРИЦА)", r.city)
        assertEquals(119796L, r.totalMinor)
        assertEquals(Instant.parse("2026-11-05T14:28:04.875Z"), r.issuedAt)
        assertEquals(JOURNAL, r.journal)
        assertEquals(FetchedItem("Pileći file/KG/0238588", 534, 77999, 41651, 10), r.items[0])
        assertEquals(FetchedItem("Bolonjeze sos/KOM/0200689", 1000, 19999, 19999, 20), r.items[1])
    }

    @Test
    fun `sends session cookie and token`() = runTest {
        client().fetch(url)
        val (first, _) = server.requests[0]
        assertEquals("application/json", first.header("Accept"))
        assertEquals(url, first.url.toString())
        val (post, body) = server.requests.single { it.first.method == "POST" }
        assertEquals("https://suf.purs.gov.rs/specifications", post.url.toString())
        assertEquals("invoiceNumber=WYBQ8YRF-WYBQ8YRF-360050&token=217e02d4-d2d5-4d25-8ebd-85a5876ef3aa", body)
        assertEquals("sid=1", post.header("Cookie"))
    }

    @Test
    fun `falls back to journal when specifications fails`() = runTest {
        server.specs = { ok("""{"success":false}""") }
        val r = (client().fetch(url) as FetchResult.Ok).receipt
        assertEquals(4, r.items.size)
        assertEquals(FetchedItem("Pileći file/KG/0238588", 1000, 77999, 77999, null), r.items[0])
    }

    @Test
    fun `falls back to journal when token page fails`() = runTest {
        server.html = { status(500) }
        assertEquals(4, (client().fetch(url) as FetchResult.Ok).receipt.items.size)
    }

    @Test
    fun `invalid receipt`() = runTest {
        server.header = { ok(header.replace("\"isValid\":true", "\"isValid\":false")) }
        assertEquals(FetchResult.Invalid("Чек не прошёл проверку налоговой"), client().fetch(url))
    }

    @Test
    fun `network error is Unavailable`() = runTest {
        server.header = { throw IOException("offline") }
        assertEquals(FetchResult.Unavailable, client().fetch(url))
        server.header = { status(503) }
        assertEquals(FetchResult.Unavailable, client().fetch(url))
    }

    @Test
    fun `unparseable header is Invalid`() = runTest {
        server.header = { ok("<html>", "text/html") }
        assertEquals(FetchResult.Invalid("Неизвестный ответ сервера"), client().fetch(url))
    }

    @Test
    fun `no items anywhere is Invalid`() = runTest {
        server.specs = { ok("""{"success":false}""") }
        server.header = { ok(header.replace(Json.encodeToString(String.serializer(), JOURNAL), "\"no items\"")) }
        assertEquals(FetchResult.Invalid("Не удалось разобрать позиции"), client().fetch(url))
    }

    @Test
    fun `each fetch has its own session`() = runTest {
        val c = client()
        c.fetch(url)
        server.html = { ok(html, "text/html") }
        c.fetch(url)
        val posts = server.requests.filter { it.first.method == "POST" }
        assertTrue(posts[1].first.header("Cookie") == null)
    }

    companion object {
        val JOURNAL = listOf(
            "============ ФИСКАЛНИ РАЧУН ============",
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
        ).joinToString("\r\n")
    }
}

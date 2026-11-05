package app.moneytracker.data.receipt

import android.util.Log
import app.moneytracker.domain.receipt.FetchResult
import app.moneytracker.domain.receipt.FetchedItem
import app.moneytracker.domain.receipt.FetchedReceipt
import app.moneytracker.domain.receipt.JournalParser
import app.moneytracker.domain.receipt.ReceiptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Чек с сайта налоговой Сербии (suf.purs.gov.rs) — три запроса:
 * 1. ссылка из QR с `Accept: application/json` → шапка, полный текст чека, `isValid`;
 * 2. та же ссылка как HTML → токен страницы и сессионная cookie;
 * 3. `POST /specifications` с номером и токеном (и cookie, иначе `{"success":false}`) → позиции.
 * Если 2 или 3 не удались, позиции разбираются из текста чека.
 */
class SufClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : ReceiptSource {

    override suspend fun fetch(url: String): FetchResult = withContext(Dispatchers.Default) {
        // Cookie — на один чек: своя сессия у каждого запроса, без общего CookieJar.
        val cookies = linkedMapOf<String, String>()
        val header = try {
            val body = call(Request.Builder().url(url).header("Accept", "application/json").build(), cookies)
                ?: return@withContext FetchResult.Unavailable
            json.decodeFromString<SufInvoiceDto>(body)
        } catch (e: IOException) {
            Log.w(TAG, "header: ${e.message}")
            return@withContext FetchResult.Unavailable
        } catch (e: SerializationException) {
            return@withContext FetchResult.Invalid(UNKNOWN_RESPONSE)
        } catch (e: IllegalArgumentException) {
            return@withContext FetchResult.Invalid(UNKNOWN_RESPONSE)
        }
        if (!header.isValid) return@withContext FetchResult.Invalid(NOT_VALID)
        val issuedAt = try {
            Instant.parse(header.invoiceResult.sdcTime)
        } catch (e: DateTimeParseException) {
            return@withContext FetchResult.Invalid(UNKNOWN_RESPONSE)
        }

        val items = specifications(url, header.invoiceResult.invoiceNumber, cookies)
            ?: header.journal?.let(JournalParser::parseItems)
            ?: return@withContext FetchResult.Invalid(NO_ITEMS)

        val r = header.invoiceRequest
        FetchResult.Ok(
            FetchedReceipt(
                taxId = r.taxId.trim(),
                officialName = r.businessName.trim(),
                locationCode = r.locationName.substringBefore('-').trim(),
                locationName = r.locationName.trim(),
                address = r.address.trim(),
                city = r.city.trim(),
                totalMinor = minor(header.invoiceResult.totalAmount, 2),
                issuedAt = issuedAt,
                journal = header.journal,
                items = items,
            ),
        )
    }

    /** null — позиции не получены (любая ошибка): тогда запасной разбор текста. */
    private suspend fun specifications(url: String, invoiceNumber: String, cookies: MutableMap<String, String>): List<FetchedItem>? = try {
        val page = call(Request.Builder().url(url).build(), cookies)
        val token = page?.let { TOKEN.find(it)?.groupValues?.get(1) }
        if (token == null) {
            null
        } else {
            val form = FormBody.Builder().add("invoiceNumber", invoiceNumber).add("token", token).build()
            call(Request.Builder().url(SPECIFICATIONS_URL).post(form).build(), cookies)
                ?.let { json.decodeFromString<SufSpecificationsDto>(it) }
                ?.takeIf { it.success && it.items.isNotEmpty() }
                ?.items?.map {
                    FetchedItem(it.name.trim(), minor(it.quantity, 3), minor(it.unitPrice, 2), minor(it.total, 2), it.labelRate)
                }
        }
    } catch (e: IOException) {
        Log.w(TAG, "specifications: ${e.message}")
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /**
     * Тело успешного ответа; null — HTTP-ошибка. Set-Cookie запоминается в [cookies] и уходит в следующие запросы.
     * Асинхронный вызов: отмена корутины (таймаут приёма, «назад») отменяет и запрос, а не ждёт его до конца.
     */
    private suspend fun call(request: Request, cookies: MutableMap<String, String>): String? {
        val withCookies = if (cookies.isEmpty()) request else {
            request.newBuilder().header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }).build()
        }
        val (setCookies, body) = suspendCancellableCoroutine { cont ->
            val call = client.newCall(withCookies)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { it.headers("Set-Cookie") to (if (it.isSuccessful) it.body?.string() else null) }
                    } catch (e: IOException) {
                        cont.resumeWithException(e)
                        return
                    }
                    cont.resume(result)
                }
            })
        }
        setCookies.forEach { c ->
            val pair = c.substringBefore(';')
            if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
        }
        return body
    }

    /** Число из JSON → целое в 10^-[scale]: 0.534 как Double — это 0.53399…, поэтому через строку. */
    private fun minor(value: Double, scale: Int): Long =
        BigDecimal(value.toString()).movePointRight(scale).setScale(0, RoundingMode.HALF_UP).longValueExact()

    companion object {
        private const val TAG = "SufClient"
        const val SPECIFICATIONS_URL = "https://suf.purs.gov.rs/specifications"
        private val TOKEN = Regex("""Token\('([0-9a-fA-F-]+)'\)""")
        const val NOT_VALID = "Чек не прошёл проверку налоговой"
        const val UNKNOWN_RESPONSE = "Неизвестный ответ сервера"
        const val NO_ITEMS = "Не удалось разобрать позиции"
    }
}

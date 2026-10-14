package app.moneytracker.data.rates

import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RefreshResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class RemoteRatesDataSourceTest {

    private val fawaz = Json.parseToJsonElement("""{"date":"2026-10-13","eur":{"rsd":117.4,"rub":93.7}}""").jsonObject
    private val erApi = Json.parseToJsonElement(
        """{"result":"success","base_code":"EUR","time_last_update_unix":1791936152,"rates":{"RSD":117.5,"RUB":93.8}}""",
    ).jsonObject

    private class FakeApi(val responses: Map<String, () -> JsonObject>) : RatesApi {
        val calls = mutableListOf<String>()
        override suspend fun get(url: String): JsonObject {
            calls += url
            return responses.getValue(url)()
        }
    }

    private val offline: () -> JsonObject = { throw IOException("offline") }

    @Test
    fun `uses primary when available`() = runTest {
        val api = FakeApi(mapOf(RemoteRatesDataSource.PRIMARY_URL to { fawaz }))
        val rates = RemoteRatesDataSource(api).fetch()
        assertEquals(RateSource.API_PRIMARY, rates.source)
        assertEquals(listOf(RemoteRatesDataSource.PRIMARY_URL), api.calls)
    }

    @Test
    fun `falls back to mirror then to er-api`() = runTest {
        val api = FakeApi(
            mapOf(
                RemoteRatesDataSource.PRIMARY_URL to offline,
                RemoteRatesDataSource.MIRROR_URL to { throw BadRatesResponseException("broken") },
                RemoteRatesDataSource.FALLBACK_URL to { erApi },
            ),
        )
        val rates = RemoteRatesDataSource(api).fetch()
        assertEquals(RateSource.API_FALLBACK, rates.source)
        assertEquals(3, api.calls.size)
    }

    @Test
    fun `all sources offline means network failure`() = runTest {
        val api = FakeApi(
            mapOf(
                RemoteRatesDataSource.PRIMARY_URL to offline,
                RemoteRatesDataSource.MIRROR_URL to offline,
                RemoteRatesDataSource.FALLBACK_URL to offline,
            ),
        )
        assertEquals(RefreshResult.Failure.Reason.NETWORK, fetchFailure(api))
    }

    @Test
    fun `http errors mean the service is unreachable, not that it answered junk`() = runTest {
        val unavailable: () -> JsonObject = { throw HttpException(Response.error<JsonObject>(503, "".toResponseBody())) }
        val api = FakeApi(
            mapOf(
                RemoteRatesDataSource.PRIMARY_URL to unavailable,
                RemoteRatesDataSource.MIRROR_URL to offline,
                RemoteRatesDataSource.FALLBACK_URL to unavailable,
            ),
        )
        assertEquals(RefreshResult.Failure.Reason.NETWORK, fetchFailure(api))
    }

    @Test
    fun `garbage from any source means bad response`() = runTest {
        val api = FakeApi(
            mapOf(
                RemoteRatesDataSource.PRIMARY_URL to offline,
                RemoteRatesDataSource.MIRROR_URL to offline,
                RemoteRatesDataSource.FALLBACK_URL to { Json.parseToJsonElement("""{"result":"error"}""").jsonObject },
            ),
        )
        assertEquals(RefreshResult.Failure.Reason.BAD_RESPONSE, fetchFailure(api))
    }

    private suspend fun fetchFailure(api: RatesApi): RefreshResult.Failure.Reason {
        try {
            RemoteRatesDataSource(api).fetch()
        } catch (e: RatesFetchException) {
            return e.reason
        }
        fail("Expected RatesFetchException")
        error("unreachable")
    }
}

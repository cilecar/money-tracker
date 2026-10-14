package app.moneytracker.data.rates

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.RateSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class RatesParsersTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `parses fawazahmed format keeping all digits`() {
        val rates = RatesParsers.parseFawazahmed(
            json("""{"date":"2026-10-13","eur":{"usd":1.08,"rsd":117.40499356,"rub":93.777076123456789012}}"""),
            RateSource.API_PRIMARY,
        )
        assertEquals(LocalDate.of(2026, 10, 13), rates.date)
        assertEquals(BigDecimal("117.40499356"), rates.perEur[CurrencyCode.RSD])
        // 20 значащих цифр — Double сохранил бы только ~16
        assertEquals(BigDecimal("93.777076123456789012"), rates.perEur[CurrencyCode.RUB])
        assertEquals(RateSource.API_PRIMARY, rates.source)
    }

    @Test
    fun `parses er-api format using update time as date`() {
        val rates = RatesParsers.parseErApi(
            json(
                """{"result":"success","base_code":"EUR","time_last_update_unix":1791936152,
                   "rates":{"EUR":1,"RSD":117.416853,"RUB":93.70523}}""",
            ),
            RateSource.API_FALLBACK,
        )
        assertEquals(LocalDate.of(2026, 10, 14), rates.date)
        assertEquals(BigDecimal("117.416853"), rates.perEur[CurrencyCode.RSD])
        assertEquals(BigDecimal("93.70523"), rates.perEur[CurrencyCode.RUB])
    }

    @Test(expected = BadRatesResponseException::class)
    fun `missing currency is rejected`() {
        RatesParsers.parseFawazahmed(json("""{"date":"2026-10-13","eur":{"rsd":117.4}}"""), RateSource.API_PRIMARY)
    }

    @Test(expected = BadRatesResponseException::class)
    fun `zero rate is rejected`() {
        RatesParsers.parseFawazahmed(json("""{"date":"2026-10-13","eur":{"rsd":0,"rub":93}}"""), RateSource.API_PRIMARY)
    }

    @Test(expected = BadRatesResponseException::class)
    fun `string rate is rejected`() {
        RatesParsers.parseFawazahmed(json("""{"date":"2026-10-13","eur":{"rsd":"117","rub":93}}"""), RateSource.API_PRIMARY)
    }

    @Test(expected = BadRatesResponseException::class)
    fun `bad date is rejected`() {
        RatesParsers.parseFawazahmed(json("""{"date":"yesterday","eur":{"rsd":117,"rub":93}}"""), RateSource.API_PRIMARY)
    }

    @Test(expected = BadRatesResponseException::class)
    fun `er-api error result is rejected`() {
        RatesParsers.parseErApi(json("""{"result":"error","error-type":"unsupported-code"}"""), RateSource.API_FALLBACK)
    }

    @Test(expected = BadRatesResponseException::class)
    fun `er-api wrong base is rejected`() {
        RatesParsers.parseErApi(
            json("""{"result":"success","base_code":"USD","time_last_update_unix":1,"rates":{"RSD":1,"RUB":1}}"""),
            RateSource.API_FALLBACK,
        )
    }
}

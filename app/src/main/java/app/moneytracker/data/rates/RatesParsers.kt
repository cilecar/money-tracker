package app.moneytracker.data.rates

import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.FetchedRates
import app.moneytracker.domain.model.RateSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class BadRatesResponseException(message: String) : RuntimeException(message)

/**
 * Числа берём как текст литерала (`JsonPrimitive.content`) и сразу в BigDecimal.
 * Если бы мы объявили DTO с полем `Double`, «117.40499356» прошло бы через двоичную
 * дробь — для курса это небольшая, но лишняя потеря точности.
 */
object RatesParsers {

    /** fawazahmed0/currency-api: `{"date":"2026-10-13","eur":{"rsd":117.40499356,"rub":93.777076,…}}` */
    fun parseFawazahmed(json: JsonObject, source: RateSource): FetchedRates {
        val date = json.string("date").let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: fail("bad date")
        val rates = json["eur"] as? JsonObject ?: fail("no 'eur' object")
        return FetchedRates(
            date = date,
            source = source,
            perEur = CurrentRates.QUOTED.associateWith { rates.decimal(it.name.lowercase()) },
        )
    }

    /** open.er-api.com: `{"result":"success","base_code":"EUR","time_last_update_unix":…,"rates":{"RSD":…}}` */
    fun parseErApi(json: JsonObject, source: RateSource): FetchedRates {
        if (json.string("result") != "success") fail("result != success")
        if (json.string("base_code") != "EUR") fail("base != EUR")
        val updated = (json["time_last_update_unix"] as? JsonPrimitive)?.longOrNull ?: fail("no update time")
        val rates = json["rates"] as? JsonObject ?: fail("no 'rates' object")
        return FetchedRates(
            date = Instant.ofEpochSecond(updated).atZone(ZoneOffset.UTC).toLocalDate(),
            source = source,
            perEur = CurrentRates.QUOTED.associateWith { rates.decimal(it.name) },
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.decimal(key: String): BigDecimal {
        val primitive = this[key] as? JsonPrimitive ?: fail("no rate for $key")
        if (primitive.isString) fail("rate for $key is a string")
        val value = primitive.content.toBigDecimalOrNull() ?: fail("rate for $key is not a number")
        if (value.signum() <= 0) fail("rate for $key is not positive")
        return value
    }

    private fun fail(message: String): Nothing = throw BadRatesResponseException(message)
}


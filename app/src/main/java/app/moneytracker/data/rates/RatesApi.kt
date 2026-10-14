package app.moneytracker.data.rates

import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Один метод с полным URL: у источников разные хосты и форматы, а разбор ответа
 * всё равно ручной (см. [RatesParsers]), так что типизированные DTO не нужны.
 */
interface RatesApi {
    @GET
    suspend fun get(@Url url: String): JsonObject
}

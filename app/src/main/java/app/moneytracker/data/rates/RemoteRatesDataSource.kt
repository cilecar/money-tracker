package app.moneytracker.data.rates

import app.moneytracker.domain.model.FetchedRates
import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class RatesFetchException(val reason: RefreshResult.Failure.Reason, cause: Throwable?) :
    Exception("Rates fetch failed: $reason", cause)

/**
 * Перебирает источники по порядку до первого успешного:
 * 1. jsDelivr CDN (fawazahmed0) — основной;
 * 2. Cloudflare Pages — официальное зеркало того же API на случай проблем с jsDelivr;
 * 3. open.er-api.com — независимый запасной источник.
 */
class RemoteRatesDataSource @Inject constructor(
    private val api: RatesApi,
) {
    private class Source(val url: String, val type: RateSource, val parse: (JsonObject, RateSource) -> FetchedRates)

    private val sources = listOf(
        Source(PRIMARY_URL, RateSource.API_PRIMARY, RatesParsers::parseFawazahmed),
        Source(MIRROR_URL, RateSource.API_PRIMARY, RatesParsers::parseFawazahmed),
        Source(FALLBACK_URL, RateSource.API_FALLBACK, RatesParsers::parseErApi),
    )

    suspend fun fetch(): FetchedRates {
        val errors = mutableListOf<Exception>()
        for (source in sources) {
            try {
                return source.parse(api.get(source.url), source.type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e
            }
        }
        // Все недоступны (нет сети или сервер отвечает ошибкой HTTP) — повторим позже.
        // Иначе хотя бы один ответил мусором.
        val reason = if (errors.all { it.isUnreachable() }) {
            RefreshResult.Failure.Reason.NETWORK
        } else {
            RefreshResult.Failure.Reason.BAD_RESPONSE
        }
        throw RatesFetchException(reason, errors.lastOrNull()).apply {
            errors.dropLast(1).forEach(::addSuppressed)
        }
    }

    /** HttpException — не IOException, но 5xx/4xx от CDN означает «источник недоступен», а не «сломан формат». */
    private fun Exception.isUnreachable() = this is IOException || this is HttpException

    companion object {
        const val PRIMARY_URL = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/eur.json"
        const val MIRROR_URL = "https://latest.currency-api.pages.dev/v1/currencies/eur.json"
        const val FALLBACK_URL = "https://open.er-api.com/v6/latest/EUR"
    }
}

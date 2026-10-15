package app.moneytracker.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Действующие сейчас курсы: что подставлять в новые расходы и что показывать в UI. */
data class CurrentRates(
    val snapshot: RateSnapshot,
    /** Откуда взят курс каждой валюты (для подписи «вручную» / «API»). */
    val effective: Map<CurrencyCode, ExchangeRate>,
    /** Время последней успешной загрузки из сети; null — курсы только ручные. */
    val lastSyncAt: Instant?,
) {
    fun isManual(currency: CurrencyCode): Boolean = effective[currency]?.source == RateSource.MANUAL

    companion object {
        /** Валюты, курс которых к EUR обязателен для снапшота (EUR к самому себе всегда 1). */
        val QUOTED = listOf(CurrencyCode.RSD, CurrencyCode.RUB)

        /**
         * Валюты, курс которых желателен, но без него курсы всё равно действуют: запасной API не знает USDT,
         * а после обновления приложения курса USDT нет до первой загрузки. Операции в такой валюте ждут курса.
         */
        val OPTIONAL = listOf(CurrencyCode.USDT)

        /** Все валюты с курсом к EUR — для загрузки, ручного ввода и экрана курсов. */
        val RATED = QUOTED + OPTIONAL

        /**
         * Выбирает действующий курс для каждой валюты:
         * 1. берётся самая свежая дата;
         * 2. на одну дату ручной курс важнее API, затем основной API важнее запасного.
         *
         * То есть ручной курс действует, пока API не принесёт курс на более позднюю дату.
         * Возвращает null, если хотя бы для одной валюты из [QUOTED] курса нет.
         */
        fun resolve(rows: List<ExchangeRate>, lastSyncAt: Instant?): CurrentRates? {
            fun pick(currency: CurrencyCode) = rows.filter { it.currency == currency }
                .maxWithOrNull(compareBy<ExchangeRate> { it.date }.thenByDescending { it.source.ordinalPriority() })
            val required = QUOTED.associateWith { pick(it) ?: return null }
            val effective = required + OPTIONAL.mapNotNull { c -> pick(c)?.let { c to it } }
            val rsd = effective.getValue(CurrencyCode.RSD)
            val rub = effective.getValue(CurrencyCode.RUB)
            return CurrentRates(
                snapshot = RateSnapshot(
                    rsdPerEur = rsd.perEur,
                    rubPerEur = rub.perEur,
                    // Если даты разные, честнее показать более старую. USDT в дату не входит: она подписывает
                    // и операции в RSD/RUB, а свою дату курс USDT показывает на экране курсов.
                    date = minOf(rsd.date, rub.date),
                    usdtPerEur = effective[CurrencyCode.USDT]?.perEur,
                ),
                effective = effective,
                lastSyncAt = lastSyncAt,
            )
        }

        /** Меньше — важнее. */
        private fun RateSource.ordinalPriority(): Int = when (this) {
            RateSource.MANUAL -> 0
            RateSource.API_PRIMARY -> 1
            RateSource.API_FALLBACK -> 2
        }
    }
}

/** Курсы, полученные из одного источника за один запрос. */
data class FetchedRates(
    val date: LocalDate,
    val source: RateSource,
    val perEur: Map<CurrencyCode, BigDecimal>,
)

sealed interface RefreshResult {
    data class Success(val rates: FetchedRates) : RefreshResult
    data class Failure(val reason: Reason) : RefreshResult {
        enum class Reason { NETWORK, BAD_RESPONSE }
    }
}

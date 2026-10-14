package app.moneytracker.domain.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class RateSource { API_PRIMARY, API_FALLBACK, MANUAL }

/** Курс одной валюты к EUR на дату. */
data class ExchangeRate(
    val date: LocalDate,
    val currency: CurrencyCode,
    val perEur: BigDecimal,
    val source: RateSource,
    val fetchedAt: Instant,
)

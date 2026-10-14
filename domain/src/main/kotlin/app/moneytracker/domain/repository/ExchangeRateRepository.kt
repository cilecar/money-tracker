package app.moneytracker.domain.repository

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.RefreshResult
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.LocalDate

interface ExchangeRateRepository {
    /** null — в БД нет курса хотя бы для одной валюты. */
    fun observeCurrent(): Flow<CurrentRates?>

    suspend fun current(): CurrentRates?

    /** Загружает курсы из сети (с запасными источниками) и сохраняет в БД. */
    suspend fun refresh(): RefreshResult

    suspend fun setManualRate(currency: CurrencyCode, perEur: BigDecimal, date: LocalDate)

    /** Убирает ручные курсы валюты — снова действует курс из API. */
    suspend fun clearManualRates(currency: CurrencyCode)
}

package app.moneytracker.data.repository

import app.moneytracker.data.db.dao.ExchangeRateDao
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.toDomain
import app.moneytracker.data.rates.RatesFetchException
import app.moneytracker.data.rates.RemoteRatesDataSource
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class ExchangeRateRepositoryImpl @Inject constructor(
    private val dao: ExchangeRateDao,
    private val remote: RemoteRatesDataSource,
    private val clock: Clock,
) : ExchangeRateRepository {

    override fun observeCurrent(): Flow<CurrentRates?> =
        combine(dao.observeLatest(), dao.observeLastSync()) { rows, lastSync ->
            CurrentRates.resolve(rows.map { it.toDomain() }, lastSync)
        }.distinctUntilChanged()

    override suspend fun current(): CurrentRates? =
        CurrentRates.resolve(dao.latest().map { it.toDomain() }, dao.lastSync())

    override suspend fun refresh(): RefreshResult = try {
        val fetched = remote.fetch()
        val now = clock.instant()
        dao.upsert(
            fetched.perEur.map { (currency, perEur) ->
                ExchangeRateEntity(fetched.date, currency, fetched.source, perEur, now)
            },
        )
        RefreshResult.Success(fetched)
    } catch (e: RatesFetchException) {
        RefreshResult.Failure(e.reason)
    }

    override suspend fun setManualRate(currency: CurrencyCode, perEur: BigDecimal, date: LocalDate) {
        dao.upsert(listOf(ExchangeRateEntity(date, currency, RateSource.MANUAL, perEur, clock.instant())))
    }

    override suspend fun clearManualRates(currency: CurrencyCode) = dao.clearManual(currency)
}

package app.moneytracker.domain.fake

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.ExchangeRate
import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.ExpenseFilter
import app.moneytracker.domain.model.ThemeMode
import app.moneytracker.domain.model.FetchedRates
import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class FakeExpenseRepository : ExpenseRepository {
    val items = MutableStateFlow<Map<Long, Expense>>(emptyMap())
    private var nextId = 1L

    override fun observe(id: Long): Flow<Expense?> = items.map { it[id] }
    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<Expense>> =
        items.map { m -> m.values.filter { it.localDate in from..to }.sortedByDescending { it.occurredAt } }
    override fun observe(filter: ExpenseFilter): Flow<List<Expense>> = items.map { m ->
        m.values.filter { e ->
            (filter.categoryIds.isEmpty() || e.categoryId in filter.categoryIds) &&
                (filter.from == null || e.localDate >= filter.from) &&
                (filter.to == null || e.localDate <= filter.to)
        }.sortedByDescending { it.occurredAt }
    }
    override fun observeRecentCategoryIds(limit: Int): Flow<List<Long>> =
        items.map { m -> m.values.sortedByDescending { it.createdAt }.map { it.categoryId }.distinct().take(limit) }
    override fun observeTags(): Flow<List<String>> = items.map { m -> m.values.flatMap { it.tags }.distinct() }
    override suspend fun get(id: Long): Expense? = items.value[id]
    override suspend fun getWithoutRates(): List<Expense> = items.value.values.filter { !it.hasRates }
    override suspend fun insert(expense: Expense): Long {
        val id = if (expense.id != 0L) expense.id else nextId++
        items.value += id to expense.copy(id = id)
        return id
    }
    override suspend fun update(expense: Expense) { items.value += expense.id to expense }
    override suspend fun delete(id: Long) { items.value -= id }
}

class FakeExchangeRateRepository(private val clock: Clock) : ExchangeRateRepository {
    val rows = MutableStateFlow<List<ExchangeRate>>(emptyList())
    var lastSyncAt: Instant? = null
    var nextResult: () -> RefreshResult = { RefreshResult.Failure(RefreshResult.Failure.Reason.NETWORK) }
    var refreshCalls = 0

    override fun observeCurrent(): Flow<CurrentRates?> = rows.map { CurrentRates.resolve(it, lastSyncAt) }
    override suspend fun current(): CurrentRates? = CurrentRates.resolve(rows.value, lastSyncAt)

    override suspend fun refresh(): RefreshResult {
        refreshCalls++
        val result = nextResult()
        if (result is RefreshResult.Success) {
            lastSyncAt = clock.instant()
            rows.value += result.rates.perEur.map { (c, v) ->
                ExchangeRate(result.rates.date, c, v, result.rates.source, clock.instant())
            }
        }
        return result
    }

    override suspend fun setManualRate(currency: CurrencyCode, perEur: BigDecimal, date: LocalDate) {
        rows.value += ExchangeRate(date, currency, perEur, RateSource.MANUAL, clock.instant())
    }

    override suspend fun clearManualRates(currency: CurrencyCode) {
        rows.value = rows.value.filterNot { it.currency == currency && it.source == RateSource.MANUAL }
    }

    companion object {
        fun success(date: LocalDate, rsd: String = "117.15", rub: String = "95.43") = RefreshResult.Success(
            FetchedRates(date, RateSource.API_PRIMARY, mapOf(CurrencyCode.RSD to BigDecimal(rsd), CurrencyCode.RUB to BigDecimal(rub))),
        )
    }
}

class FakeSettingsRepository : SettingsRepository {
    override val settings = MutableStateFlow(AppSettings())
    override suspend fun setInputCurrency(currency: CurrencyCode) { settings.value = settings.value.copy(inputCurrency = currency) }
    override suspend fun setDisplayCurrency(currency: CurrencyCode) { settings.value = settings.value.copy(displayCurrency = currency) }
    override suspend fun setDefaultPaymentMethod(id: Long?) { settings.value = settings.value.copy(defaultPaymentMethodId = id) }
    override suspend fun setThemeMode(mode: ThemeMode) { settings.value = settings.value.copy(themeMode = mode) }
    override suspend fun setDynamicColor(enabled: Boolean) { settings.value = settings.value.copy(dynamicColor = enabled) }
}

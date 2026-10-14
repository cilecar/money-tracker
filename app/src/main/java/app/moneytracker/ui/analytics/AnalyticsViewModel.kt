package app.moneytracker.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.AnalyticsPeriod
import app.moneytracker.domain.model.AnalyticsReport
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.ObserveAnalyticsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class PeriodType { WEEK, MONTH, YEAR, CUSTOM }

data class AnalyticsUiState(
    val period: AnalyticsPeriod,
    val report: AnalyticsReport? = null,
    val canGoNext: Boolean = false,
) {
    val type: PeriodType get() = when (period) {
        is AnalyticsPeriod.Week -> PeriodType.WEEK
        is AnalyticsPeriod.Month -> PeriodType.MONTH
        is AnalyticsPeriod.Year -> PeriodType.YEAR
        is AnalyticsPeriod.Custom -> PeriodType.CUSTOM
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val observeAnalytics: ObserveAnalyticsUseCase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    private val today: LocalDate get() = LocalDate.now(clock)
    private val period = MutableStateFlow<AnalyticsPeriod>(AnalyticsPeriod.monthOf(today))
    private val currency = settings.settings.map { it.displayCurrency }.distinctUntilChanged()

    val state: StateFlow<AnalyticsUiState> = combine(period, currency) { p, c -> p to c }
        .flatMapLatest { (p, c) ->
            observeAnalytics(p, c).map { report ->
                AnalyticsUiState(p, report, canGoNext = !p.next().from.isAfter(today))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState(period.value))

    /** Смена типа периода: берём период нового типа, содержащий «опорную» дату текущего. */
    fun setType(type: PeriodType) {
        val current = period.value
        val anchor = if (today in current) today else current.from
        period.value = when (type) {
            PeriodType.WEEK -> AnalyticsPeriod.weekOf(anchor)
            PeriodType.MONTH -> AnalyticsPeriod.monthOf(anchor)
            PeriodType.YEAR -> AnalyticsPeriod.yearOf(anchor)
            PeriodType.CUSTOM -> return // произвольный задаётся через выбор дат
        }
    }

    fun setCustom(from: LocalDate, to: LocalDate) {
        period.value = AnalyticsPeriod.Custom(from, to)
    }

    fun previous() {
        period.value = period.value.previous()
    }

    fun next() {
        if (state.value.canGoNext) period.value = period.value.next()
    }

    fun setCurrency(c: CurrencyCode) {
        viewModelScope.launch { settings.setDisplayCurrency(c) }
    }
}

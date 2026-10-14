package app.moneytracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.RefreshRatesUseCase
import app.moneytracker.domain.usecase.RunRecurringUseCase
import app.moneytracker.domain.usecase.SetManualRateUseCase
import app.moneytracker.work.RatesSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    ratesRepository: ExchangeRateRepository,
    private val refreshRates: RefreshRatesUseCase,
    private val setManualRate: SetManualRateUseCase,
    private val ratesSyncScheduler: RatesSyncScheduler,
    private val runRecurring: RunRecurringUseCase,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val promptRequested = MutableStateFlow(false)

    /**
     * Показывать ли предложение ввести курс вручную: загрузка при старте не удалась
     * и курсов в БД нет. Само скрывается, как только курсы появятся (например, фоновая
     * задача докачала их, когда появилась сеть).
     */
    val showNoRatesPrompt: StateFlow<Boolean> =
        combine(promptRequested, ratesRepository.observeCurrent()) { requested, current ->
            requested && current == null
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Регулярные платежи на сегодня появятся сразу при открытии, не дожидаясь фоновой задачи.
        viewModelScope.launch { runRecurring() }
        // Сразу при запуске: если курсов нет или они старше 12 ч — грузим, не дожидаясь WorkManager.
        viewModelScope.launch {
            val result = refreshRates.ifStale()
            if (result is RefreshResult.Failure) {
                ratesSyncScheduler.syncWhenOnline()
                if (ratesRepository.current() == null) promptRequested.value = true
            }
        }
    }

    fun dismissNoRatesPrompt() {
        promptRequested.value = false
    }

    fun setManualRates(values: Map<CurrencyCode, BigDecimal>) {
        promptRequested.value = false
        viewModelScope.launch { values.forEach { (currency, rate) -> setManualRate(currency, rate) } }
    }
}

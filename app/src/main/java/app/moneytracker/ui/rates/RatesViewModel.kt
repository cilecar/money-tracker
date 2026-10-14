package app.moneytracker.ui.rates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.usecase.RefreshRatesUseCase
import app.moneytracker.domain.usecase.SetManualRateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

data class RatesUiState(
    val loading: Boolean = true,
    val current: CurrentRates? = null,
    val refreshing: Boolean = false,
)

sealed interface RatesEvent {
    data object Updated : RatesEvent
    data class Failed(val reason: RefreshResult.Failure.Reason) : RatesEvent
}

@HiltViewModel
class RatesViewModel @Inject constructor(
    private val repository: ExchangeRateRepository,
    private val refreshRates: RefreshRatesUseCase,
    private val setManualRate: SetManualRateUseCase,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<RatesUiState> =
        combine(repository.observeCurrent(), refreshing) { current, refreshing ->
            RatesUiState(loading = false, current = current, refreshing = refreshing)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RatesUiState())

    /** Одноразовые события (Snackbar) — через Channel, чтобы не показывать повторно при повороте. */
    private val _events = Channel<RatesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val result = refreshRates()
            refreshing.value = false
            _events.send(
                when (result) {
                    is RefreshResult.Success -> RatesEvent.Updated
                    is RefreshResult.Failure -> RatesEvent.Failed(result.reason)
                },
            )
        }
    }

    fun setManual(values: Map<CurrencyCode, BigDecimal>) {
        viewModelScope.launch { values.forEach { (currency, rate) -> setManualRate(currency, rate) } }
    }

    fun clearManual(currency: CurrencyCode) {
        viewModelScope.launch { repository.clearManualRates(currency) }
    }
}

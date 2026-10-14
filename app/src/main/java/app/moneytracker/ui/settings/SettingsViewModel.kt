package app.moneytracker.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.ThemeMode
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val accounts: List<Account> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    accounts: AccountRepository,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> =
        combine(repository.settings, accounts.observeAll()) { s, m -> SettingsUiState(s, m) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setDefaultAccount(id: Long?) = launch { repository.setDefaultAccount(id) }
    fun setDisplayCurrency(c: CurrencyCode) = launch { repository.setDisplayCurrency(c) }
    fun setInputCurrency(c: CurrencyCode) = launch { repository.setInputCurrency(c) }
    fun setTheme(mode: ThemeMode) = launch { repository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = launch { repository.setDynamicColor(enabled) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

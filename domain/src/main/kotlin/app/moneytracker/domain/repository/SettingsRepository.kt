package app.moneytracker.domain.repository

import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setInputCurrency(currency: CurrencyCode)
    suspend fun setDisplayCurrency(currency: CurrencyCode)
    suspend fun setDefaultAccount(id: Long?)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setAutoCapture(enabled: Boolean)
    suspend fun setCaptureDebug(enabled: Boolean)
}

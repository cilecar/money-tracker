package app.moneytracker.domain.repository

import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setInputCurrency(currency: CurrencyCode)
    suspend fun setDisplayCurrency(currency: CurrencyCode)
    suspend fun setDefaultPaymentMethod(id: Long?)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
}

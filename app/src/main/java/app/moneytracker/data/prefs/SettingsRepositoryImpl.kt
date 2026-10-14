package app.moneytracker.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.moneytracker.domain.model.AppSettings
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ThemeMode
import app.moneytracker.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    /**
     * Нечитаемый файл настроек (ошибка диска) — работаем на значениях по умолчанию, а не падаем
     * в каждом подписчике. Битый файл DataStore сам заменит пустым (corruptionHandler в DataModule).
     */
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val defaults = AppSettings()
            AppSettings(
                inputCurrency = prefs[INPUT_CURRENCY].toEnumOr(defaults.inputCurrency),
                displayCurrency = prefs[DISPLAY_CURRENCY].toEnumOr(defaults.displayCurrency),
                defaultPaymentMethodId = prefs[DEFAULT_PAYMENT_METHOD],
                themeMode = prefs[THEME_MODE].toEnumOr(defaults.themeMode),
                dynamicColor = prefs[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            )
        }
        .distinctUntilChanged()

    override suspend fun setInputCurrency(currency: CurrencyCode) = set(INPUT_CURRENCY, currency.name)
    override suspend fun setDisplayCurrency(currency: CurrencyCode) = set(DISPLAY_CURRENCY, currency.name)
    override suspend fun setThemeMode(mode: ThemeMode) = set(THEME_MODE, mode.name)
    override suspend fun setDynamicColor(enabled: Boolean) = set(DYNAMIC_COLOR, enabled)

    override suspend fun setDefaultPaymentMethod(id: Long?) {
        dataStore.edit { if (id == null) it.remove(DEFAULT_PAYMENT_METHOD) else it[DEFAULT_PAYMENT_METHOD] = id }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    /** Храним enum по имени; неизвестное значение (например, после переименования) → дефолт. */
    private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private companion object {
        val INPUT_CURRENCY = stringPreferencesKey("input_currency")
        val DISPLAY_CURRENCY = stringPreferencesKey("display_currency")
        val DEFAULT_PAYMENT_METHOD = longPreferencesKey("default_payment_method_id")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}

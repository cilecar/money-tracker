package app.moneytracker.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val inputCurrency: CurrencyCode = CurrencyCode.RSD,
    val displayCurrency: CurrencyCode = CurrencyCode.RSD,
    val defaultPaymentMethodId: Long? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

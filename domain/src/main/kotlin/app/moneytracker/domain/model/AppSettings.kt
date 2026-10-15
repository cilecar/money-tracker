package app.moneytracker.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val inputCurrency: CurrencyCode = CurrencyCode.RSD,
    val displayCurrency: CurrencyCode = CurrencyCode.RSD,
    val defaultAccountId: Long? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Автозахват расходов из уведомлений — только после явного включения. */
    val autoCapture: Boolean = false,
    /** Отладка автозахвата: хранить сырые уведомления из белого списка (локально, последние 50). */
    val captureDebug: Boolean = false,
)

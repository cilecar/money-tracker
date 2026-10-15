package app.moneytracker.domain.capture

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import javax.inject.Inject

/**
 * Google Wallet (Google Pay): заголовок — мерчант, текст — «RSD1,600 with Bybit Mastercard ••1234».
 * Формат суммы зависит от языка телефона: «RSD1,600», «1.600 RSD», «RSD 1.600,00», «€12.50», «1 250,00 ₽» —
 * поэтому валюта ищется и до, и после числа, а само число разбирает [Money.parse] (он отличает
 * разделитель тысяч от десятичного). Версия для экрана блокировки («Purchase complete / View the details»)
 * суммы не содержит и пропускается.
 */
class GoogleWalletParser @Inject constructor() : NotificationParser {
    override val packageName: String = PACKAGE

    override fun parse(notification: RawNotification): ParsedPurchase? {
        val merchant = notification.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val text = listOfNotNull(notification.text, notification.bigText).joinToString(" ")
        // Валюта до числа («RSD1,600», «€12.50») или после («1.600 RSD»); берём то, что встретилось раньше.
        val (code, number) = listOfNotNull(
            PREFIX.find(text)?.let { it.range.first to (it.groupValues[1] to it.groupValues[2]) },
            SUFFIX.find(text)?.let { it.range.first to (it.groupValues[2] to it.groupValues[1]) },
        ).minByOrNull { it.first }?.second ?: return null
        val currency = currencyOf(code) ?: return null // валюты вне RSD/EUR/RUB пока не храним
        val amount = Money.parse(number, currency)?.takeIf { it.minor > 0 } ?: return null
        return ParsedPurchase(
            source = PACKAGE,
            amount = amount,
            merchant = merchant,
            cardSuffix = CARD.find(text)?.groupValues?.get(1),
            occurredAt = notification.postedAt,
            notificationKey = notification.key,
        )
    }

    private fun currencyOf(code: String): CurrencyCode? = when (code.lowercase().trimEnd('.')) {
        "rsd", "дин", "din" -> CurrencyCode.RSD
        "eur", "€" -> CurrencyCode.EUR
        "rub", "₽", "руб" -> CurrencyCode.RUB
        else -> null
    }

    companion object {
        const val PACKAGE = "com.google.android.apps.walletnfcrel"

        private const val CUR = """RSD|EUR|RUB|USD|GBP|CHF|€|₽|\$|£|дин\.?|din\.?|руб\.?"""
        // Число: группы по 3 через пробел/точку/запятую и необязательные 1–2 десятичных знака.
        private const val NUM = """\d{1,3}(?:[\s\u00A0\u202F.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""
        // Между валютой и числом бывает неразрывный пробел (ru: «1 250,00 ₽») — \s в Java его не ловит.
        private const val GAP = """[\s\u00A0\u202F]?"""
        private val PREFIX = Regex("""($CUR)$GAP($NUM)""", RegexOption.IGNORE_CASE)
        private val SUFFIX = Regex("""($NUM)$GAP($CUR)""", RegexOption.IGNORE_CASE)
        private val CARD = Regex("""[•·*]{2,}\s?(\d{4})""")
    }
}

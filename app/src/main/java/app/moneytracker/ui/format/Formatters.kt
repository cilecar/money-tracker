package app.moneytracker.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Курс: 117,405 / 1,2520 — до 4 знаков, минимум 2, с разделителями тысяч по локали. */
fun formatRate(value: BigDecimal, locale: Locale): String =
    DecimalFormat("#,##0.00##", DecimalFormatSymbols.getInstance(locale)).apply {
        isParseBigDecimal = true
    }.format(value)

fun formatDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

fun formatDateTime(instant: Instant, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    instant.atZone(zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))

/**
 * Разбор положительного десятичного числа из поля ввода: «117,4», «117.4», «1 117,4».
 * Без Double: строка сразу превращается в BigDecimal.
 */
fun parsePositiveDecimal(text: String): BigDecimal? {
    val normalized = text.trim().filterNot { it.isWhitespace() || it == ' ' }.replace(',', '.')
    if (normalized.count { it == '.' } > 1) return null
    return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
}

/** Символ валюты для подписей: «€», «₽», для динара — код (у «дин.» нет общепринятого символа). */
val CurrencyCode.symbol: String
    get() = when (this) {
        CurrencyCode.RSD -> "RSD"
        CurrencyCode.EUR -> "€"
        CurrencyCode.RUB -> "₽"
    }

/**
 * «1 250 RSD», «12,50 €». Копейки показываем, только если они не нулевые:
 * в динарах и рублях дробная часть почти всегда 0 и только шумит.
 */
fun formatMoney(money: Money, locale: Locale, approx: Boolean = false): String {
    var factor = 1L
    repeat(money.currency.fractionDigits) { factor *= 10 }
    val pattern = if (money.minor % factor != 0L) "#,##0.00" else "#,##0"
    val number = DecimalFormat(pattern, DecimalFormatSymbols.getInstance(locale)).format(money.amount)
    return (if (approx) "≈ " else "") + number + " " + money.currency.symbol
}

package app.moneytracker.ui.expenses.edit

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import java.text.DecimalFormatSymbols
import java.util.Locale

sealed interface KeypadKey {
    data class Digit(val value: Int) : KeypadKey
    data object Decimal : KeypadKey
    data object Backspace : KeypadKey
    data object Clear : KeypadKey
}

/**
 * Состояние суммы, набираемой на цифровой клавиатуре. Хранится как строки цифр,
 * а не как число: так «12,» и «12,0» отображаются ровно как набраны, а в минорные
 * единицы переводится целочисленно — без Double и без округлений.
 */
data class AmountInput(
    val integer: String = "",
    /** null — разделитель ещё не нажат; "" — нажат, цифр после него нет. */
    val fraction: String? = null,
) {
    val isEmpty: Boolean get() = integer.isEmpty() && fraction == null

    fun press(key: KeypadKey, fractionDigits: Int = 2): AmountInput = when (key) {
        is KeypadKey.Digit -> when {
            fraction != null ->
                if (fraction.length < fractionDigits) copy(fraction = fraction + key.value) else this
            integer == "0" -> copy(integer = key.value.toString())
            integer.length >= MAX_INTEGER_DIGITS -> this
            else -> copy(integer = integer + key.value)
        }
        KeypadKey.Decimal -> if (fraction == null && fractionDigits > 0) {
            copy(integer = integer.ifEmpty { "0" }, fraction = "")
        } else {
            this
        }
        KeypadKey.Backspace -> when {
            fraction == null -> copy(integer = integer.dropLast(1))
            fraction.isEmpty() -> copy(fraction = null)
            else -> copy(fraction = fraction.dropLast(1))
        }
        KeypadKey.Clear -> AmountInput()
    }

    /** Если у новой валюты меньше знаков после запятой — лишние отрезаются. */
    fun fitTo(currency: CurrencyCode): AmountInput =
        if (fraction != null && fraction.length > currency.fractionDigits) {
            copy(fraction = fraction.take(currency.fractionDigits))
        } else {
            this
        }

    fun toMoney(currency: CurrencyCode): Money {
        val digits = currency.fractionDigits
        val intPart = integer.ifEmpty { "0" }.toLong()
        val fracPart = (fraction ?: "").padEnd(digits, '0').take(digits).ifEmpty { "0" }.toLong()
        var factor = 1L
        repeat(digits) { factor *= 10 }
        return Money(intPart * factor + fracPart, currency)
    }

    /** «1 234,5» — группировка и разделитель по локали. */
    fun display(locale: Locale): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val grouped = integer.ifEmpty { "0" }.reversed().chunked(3).joinToString(symbols.groupingSeparator.toString()).reversed()
        return if (fraction == null) grouped else grouped + symbols.decimalSeparator + fraction
    }

    companion object {
        /** До 999 999 999 — тот же предел, что у [Money.parse]. */
        const val MAX_INTEGER_DIGITS = Money.MAX_INTEGER_DIGITS

        fun from(money: Money): AmountInput {
            var factor = 1L
            repeat(money.currency.fractionDigits) { factor *= 10 }
            val integer = (money.minor / factor).toString()
            val frac = money.minor % factor
            return if (frac == 0L) {
                AmountInput(integer)
            } else {
                AmountInput(integer, frac.toString().padStart(money.currency.fractionDigits, '0').trimEnd('0'))
            }
        }
    }
}

/**
 * Выбор счёта переключает валюту, а набранное число остаётся: «100 RSD» молча становится «100 €».
 * Возвращает валюту, о которой надо напомнить («была RSD»), или null. При нескольких сменах подряд
 * помним самую первую; вернулись к ней — напоминать не о чем. Пустая сумма — тоже.
 */
fun AmountInput.currencySwitchedFrom(current: CurrencyCode, accountCurrency: CurrencyCode, pending: CurrencyCode?): CurrencyCode? {
    if (isEmpty) return null
    return (pending ?: current).takeIf { it != accountCurrency }
}

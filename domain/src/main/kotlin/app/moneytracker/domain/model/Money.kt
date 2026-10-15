package app.moneytracker.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Денежная сумма в минорных единицах: `Money(12345, EUR)` = 123.45 €.
 *
 * Long не теряет точность при сложении (в отличие от Double) и дёшев для SQL-агрегаций.
 * BigDecimal используется только как промежуточный тип при конвертации и вводе.
 */
data class Money(val minor: Long, val currency: CurrencyCode) : Comparable<Money> {

    /** Сумма в основных единицах с точным масштабом, например 123.45. */
    val amount: BigDecimal get() = BigDecimal.valueOf(minor, currency.fractionDigits)

    val isZero: Boolean get() = minor == 0L

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minor, other.minor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minor, other.minor), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minor.compareTo(other.minor)
    }

    private fun requireSameCurrency(other: Money) =
        require(currency == other.currency) { "Currency mismatch: $currency vs ${other.currency}" }

    override fun toString(): String = "${amount.toPlainString()} $currency"

    companion object {
        fun zero(currency: CurrencyCode) = Money(0, currency)

        /**
         * Округляет [amount] до минорных единиц. HALF_EVEN («банковское») не даёт
         * систематического смещения вверх при суммировании многих округлённых значений.
         *
         * Внимание: `BigInteger.longValueExact()` есть на Android только с API 31,
         * поэтому используем `BigDecimal.longValueExact()` (доступен везде).
         */
        fun of(
            amount: BigDecimal,
            currency: CurrencyCode,
            rounding: RoundingMode = RoundingMode.HALF_EVEN,
        ): Money = Money(
            minor = amount.setScale(currency.fractionDigits, rounding)
                .movePointRight(currency.fractionDigits)
                .longValueExact(),
            currency = currency,
        )

        /**
         * Разбирает пользовательский ввод: «1 234,5», «1234.50», «1.234,56» (EU), «1,234.56» (US).
         *
         * Если встречаются и `.`, и `,` — десятичный последний из них. Если только один вид:
         * встречается один раз и после него не ровно 3 цифры — десятичный («12,5»), иначе это
         * разделитель тысяч («1.234», «1.000.000»). Группы тысяч обязаны быть по 3 цифры.
         * Для валют с [CurrencyCode.ambiguousGroupIsError] (USDT) такой одиночный «1.234» — ошибка:
         * неясно, тысячи это или три знака дроби.
         *
         * Возвращает null для пустой, некорректной, отрицательной строки, если дробных знаков
         * больше, чем у валюты, или целая часть длиннее [MAX_INTEGER_DIGITS]: лучше показать
         * ошибку, чем молча угадать другое число.
         */
        fun parse(text: String, currency: CurrencyCode): Money? {
            val body = text.filterNot { it.isWhitespace() || it == '\'' }
            if (body.isEmpty() || body.any { !it.isDigit() && it != '.' && it != ',' }) return null

            val separators = body.filter { it == '.' || it == ',' }
            val ambiguous = separators.length == 1 && body.length - body.indexOf(separators[0]) - 1 == 3
            if (ambiguous && currency.ambiguousGroupIsError) return null
            val decimal: Char? = when {
                separators.isEmpty() -> null
                separators.toSet().size == 2 -> separators.last()
                separators.length == 1 && !ambiguous -> separators[0]
                else -> null
            }
            val intText = if (decimal != null) body.substringBeforeLast(decimal) else body
            val fracText = if (decimal != null) body.substringAfterLast(decimal) else ""
            if (decimal != null && decimal in intText) return null

            val grouping = separators.firstOrNull { it != decimal }
            val intValid = if (grouping == null) {
                intText.all { it.isDigit() }
            } else {
                Regex("""[1-9]\d{0,2}(${Regex.escape(grouping.toString())}\d{3})+""").matches(intText)
            }
            if (!intValid || (intText.isEmpty() && fracText.isEmpty())) return null

            val value = BigDecimal("${intText.filter { it.isDigit() }.ifEmpty { "0" }}.${fracText.ifEmpty { "0" }}")
            if (value.stripTrailingZeros().scale() > currency.fractionDigits) return null
            if (value.precision() - value.scale() > MAX_INTEGER_DIGITS) return null
            return of(value, currency)
        }

        /**
         * Максимум цифр в целой части вводимой суммы. Дело не в самом Long, а в конвертации:
         * сумма в ×100 раз больше (EUR→RSD) обязана влезать в Long, иначе расход нельзя сохранить,
         * а регулярный платёж с такой суммой ронял бы каждый запуск.
         */
        const val MAX_INTEGER_DIGITS = 9
    }
}

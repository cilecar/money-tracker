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
         * Последний из разделителей `.`/`,` считается десятичным, если после него ≤ 2 цифр.
         * Возвращает null для пустой или некорректной строки.
         */
        fun parse(text: String, currency: CurrencyCode): Money? {
            val cleaned = text.trim().filterNot { it.isWhitespace() || it == '\'' }
            if (cleaned.isEmpty()) return null
            val negative = cleaned.startsWith('-')
            val body = cleaned.removePrefix("-")
            if (body.isEmpty() || body.any { !it.isDigit() && it != '.' && it != ',' }) return null

            val lastSep = body.indexOfLast { it == '.' || it == ',' }
            val normalized = if (lastSep >= 0 && body.length - lastSep - 1 <= currency.fractionDigits &&
                body.count { it == body[lastSep] } == 1
            ) {
                val intPart = body.substring(0, lastSep).filter { it.isDigit() }
                "$intPart.${body.substring(lastSep + 1)}"
            } else {
                body.filter { it.isDigit() }
            }
            if (normalized.isEmpty() || normalized == ".") return null

            return runCatching {
                val value = BigDecimal(normalized)
                if (value.scale() > currency.fractionDigits) return null
                of(if (negative) value.negate() else value, currency)
            }.getOrNull()
        }
    }
}

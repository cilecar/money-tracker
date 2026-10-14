package app.moneytracker.domain.model

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/**
 * Снапшот курсов относительно EUR (так отдаёт API): сколько RSD и RUB стоит 1 EUR.
 * Хранится в каждом расходе, чтобы история не менялась при обновлении курсов.
 *
 * Любой кросс-курс выводится из двух чисел: X→Y = perEur(Y) / perEur(X).
 */
data class RateSnapshot(
    val rsdPerEur: BigDecimal,
    val rubPerEur: BigDecimal,
    /** Дата, на которую действует курс (не дата расхода). */
    val date: LocalDate,
) {
    init {
        require(rsdPerEur.signum() > 0) { "rsdPerEur must be positive" }
        require(rubPerEur.signum() > 0) { "rubPerEur must be positive" }
    }

    fun perEur(currency: CurrencyCode): BigDecimal = when (currency) {
        CurrencyCode.EUR -> BigDecimal.ONE
        CurrencyCode.RSD -> rsdPerEur
        CurrencyCode.RUB -> rubPerEur
    }

    /** Сколько единиц [to] стоит 1 единица [from]. Для отображения курса в UI. */
    fun rate(from: CurrencyCode, to: CurrencyCode): BigDecimal =
        if (from == to) BigDecimal.ONE else perEur(to).divide(perEur(from), MATH_CONTEXT)

    /**
     * Конвертация с одним округлением в самом конце. Промежуточные значения держим
     * с точностью 34 значащих цифры (DECIMAL128), чтобы RUB→EUR→RSD не копил ошибку.
     */
    fun convert(money: Money, to: CurrencyCode): Money {
        if (money.currency == to) return money
        val result = money.amount
            .multiply(perEur(to), MATH_CONTEXT)
            .divide(perEur(money.currency), MATH_CONTEXT)
        return Money.of(result, to)
    }

    fun convertAll(money: Money) = ConvertedAmounts(
        rsdMinor = convert(money, CurrencyCode.RSD).minor,
        eurMinor = convert(money, CurrencyCode.EUR).minor,
        rubMinor = convert(money, CurrencyCode.RUB).minor,
    )

    private companion object {
        val MATH_CONTEXT: MathContext = MathContext.DECIMAL128
    }
}

/**
 * Сумма расхода, заранее посчитанная во всех валютах по снапшоту.
 * Благодаря этому аналитика в любой валюте — просто `SUM(eur_minor)` в SQL.
 */
data class ConvertedAmounts(val rsdMinor: Long, val eurMinor: Long, val rubMinor: Long) {
    operator fun get(currency: CurrencyCode): Money = when (currency) {
        CurrencyCode.RSD -> Money(rsdMinor, currency)
        CurrencyCode.EUR -> Money(eurMinor, currency)
        CurrencyCode.RUB -> Money(rubMinor, currency)
    }
}

package app.moneytracker.domain.model

import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate

/**
 * Снапшот курсов относительно EUR (так отдаёт API): сколько RSD, RUB и USDT стоит 1 EUR.
 * Хранится в каждом расходе, чтобы история не менялась при обновлении курсов.
 *
 * Любой кросс-курс выводится из этих чисел: X→Y = perEur(Y) / perEur(X).
 */
data class RateSnapshot(
    val rsdPerEur: BigDecimal,
    val rubPerEur: BigDecimal,
    /** Дата, на которую действует курс (не дата расхода). */
    val date: LocalDate,
    /**
     * null — в снапшоте нет USDT: записи до появления USDT в приложении или курса USDT ещё не было
     * (запасной API его не отдаёт). Такой снапшот не годится для сумм в USDT, см. [supports].
     */
    val usdtPerEur: BigDecimal? = null,
) {
    init {
        require(rsdPerEur.signum() > 0) { "rsdPerEur must be positive" }
        require(rubPerEur.signum() > 0) { "rubPerEur must be positive" }
        require(usdtPerEur == null || usdtPerEur.signum() > 0) { "usdtPerEur must be positive" }
    }

    /** Есть ли в снапшоте курс [currency]: только тогда её можно конвертировать. */
    fun supports(currency: CurrencyCode): Boolean = currency != CurrencyCode.USDT || usdtPerEur != null

    /** @throws IllegalStateException если курса нет, см. [supports]. */
    fun perEur(currency: CurrencyCode): BigDecimal = when (currency) {
        CurrencyCode.EUR -> BigDecimal.ONE
        CurrencyCode.RSD -> rsdPerEur
        CurrencyCode.RUB -> rubPerEur
        CurrencyCode.USDT -> checkNotNull(usdtPerEur) { "No USDT rate in snapshot of $date" }
    }

    /** Курс для UI или null, если одной из валют в снапшоте нет. */
    fun rateOrNull(from: CurrencyCode, to: CurrencyCode): BigDecimal? =
        if (supports(from) && supports(to)) rate(from, to) else null

    /** Конвертация для UI или null, если курса нет. */
    fun convertOrNull(money: Money, to: CurrencyCode): Money? =
        if (supports(money.currency) && supports(to)) convert(money, to) else null

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

    /** Суммы в валютах аналитики. Для суммы в USDT снапшот обязан содержать курс USDT. */
    fun convertAll(money: Money) = ConvertedAmounts(
        rsdMinor = convert(money, CurrencyCode.RSD).minor,
        eurMinor = convert(money, CurrencyCode.EUR).minor,
        rubMinor = convert(money, CurrencyCode.RUB).minor,
    )

    private companion object {
        val MATH_CONTEXT: MathContext = MathContext.DECIMAL128
    }
}

/** Снапшот, годный для суммы в [currency]; иначе null — операция ждёт курса, как без снапшота вообще. */
fun RateSnapshot?.usableFor(currency: CurrencyCode): RateSnapshot? = this?.takeIf { it.supports(currency) }

/**
 * Сумма расхода, заранее посчитанная во всех валютах аналитики ([CurrencyCode.DISPLAY]) по снапшоту.
 * Благодаря этому аналитика в любой валюте — просто `SUM(eur_minor)` в SQL.
 */
data class ConvertedAmounts(val rsdMinor: Long, val eurMinor: Long, val rubMinor: Long) {
    /** @throws IllegalArgumentException для валюты не из [CurrencyCode.DISPLAY]. */
    operator fun get(currency: CurrencyCode): Money = when (currency) {
        CurrencyCode.RSD -> Money(rsdMinor, currency)
        CurrencyCode.EUR -> Money(eurMinor, currency)
        CurrencyCode.RUB -> Money(rubMinor, currency)
        CurrencyCode.USDT -> throw IllegalArgumentException("USDT is not a display currency")
    }

    operator fun unaryMinus() = ConvertedAmounts(-rsdMinor, -eurMinor, -rubMinor)
}

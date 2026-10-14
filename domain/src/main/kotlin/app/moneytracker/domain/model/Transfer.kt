package app.moneytracker.domain.model

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Перевод между своими счетами. Обе суммы хранятся как есть — в валютах счетов: при обмене
 * [toAmount] / [fromAmount] и есть фактический курс обменника. В аналитику расходов и доходов
 * перевод не входит, меняет только балансы. Комиссия — отдельный обычный расход ([feeExpenseId]).
 */
data class Transfer(
    val id: Long = 0,
    val fromAccountId: Long,
    val toAccountId: Long,
    /** Сколько списано со счёта-источника, в его валюте. */
    val fromAmount: Money,
    /** Сколько зачислено на счёт-получатель, в его валюте. */
    val toAmount: Money,
    val feeExpenseId: Long? = null,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val comment: String? = null,
    /** Курс API на момент перевода — только чтобы показать рядом с курсом обменника. */
    val apiRates: RateSnapshot? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(fromAccountId != toAccountId) { "Transfer needs two different accounts" }
        require(fromAmount.minor > 0 && toAmount.minor > 0) { "Transfer amounts must be positive" }
    }

    val isExchange: Boolean get() = fromAmount.currency != toAmount.currency

    /** Фактический курс: сколько единиц валюты получателя дали за 1 единицу валюты источника. */
    val exchangeRate: BigDecimal? get() = if (isExchange) rateOf(fromAmount, toAmount) else null

    /** Курс API той же пары на момент перевода; без пересчёта сумм. */
    val apiRate: BigDecimal? get() = if (isExchange) apiRates?.rate(fromAmount.currency, toAmount.currency) else null

    fun withOccurredAt(instant: Instant, zone: ZoneId, now: Instant) =
        copy(occurredAt = instant, localDate = instant.atZone(zone).toLocalDate(), updatedAt = now)

    companion object {
        private val MATH = MathContext.DECIMAL64

        /** Курс по двум суммам: [to] / [from]. */
        fun rateOf(from: Money, to: Money): BigDecimal = to.amount.divide(from.amount, MATH)

        /**
         * Насколько курс обменника хуже (−) или лучше (+) курса API, в процентах с двумя знаками.
         * null — сравнивать не с чем.
         */
        fun deviationPercent(actual: BigDecimal?, api: BigDecimal?): BigDecimal? {
            if (actual == null || api == null || api.signum() == 0) return null
            return actual.subtract(api).multiply(BigDecimal(100)).divide(api, 2, java.math.RoundingMode.HALF_UP)
        }

        fun create(
            fromAccountId: Long,
            toAccountId: Long,
            fromAmount: Money,
            toAmount: Money,
            occurredAt: Instant,
            zone: ZoneId,
            apiRates: RateSnapshot?,
            now: Instant,
            comment: String? = null,
        ) = Transfer(
            fromAccountId = fromAccountId,
            toAccountId = toAccountId,
            fromAmount = fromAmount,
            toAmount = toAmount,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            comment = cleanComment(comment),
            apiRates = apiRates,
            createdAt = now,
            updatedAt = now,
        )
    }
}

/** Строка ленты для перевода. Комиссия показывается отдельной строкой — это обычный расход. */
data class TransferItem(val transfer: Transfer, val from: Account?, val to: Account?) : OperationItem {
    override val key get() = "t-${transfer.id}"
    override val occurredAt: Instant get() = transfer.occurredAt
    override val localDate: LocalDate get() = transfer.localDate
}

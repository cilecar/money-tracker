package app.moneytracker.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Столбец графика: начало интервала (день или первое число месяца) и сумма. */
data class Bucket(val start: LocalDate, val amount: Money)

/** Доля категории верхнего уровня (подкатегории свёрнуты в родителя). */
data class CategorySlice(
    /** null — расходы с неизвестной категорией (на практике не бывает, но не теряем деньги). */
    val category: Category?,
    val amount: Money,
    val previous: Money,
    /** Доля от итога 0..1 — только для отрисовки, не для денег. */
    val share: Float,
)

data class PaymentSlice(val method: Account?, val amount: Money, val share: Float)

data class AnalyticsReport(
    val period: AnalyticsPeriod,
    val currency: CurrencyCode,
    val total: Total,
    val previousTotal: Total,
    /**
     * Прошлый период на «это же число» — если текущий период ещё идёт. Сравнивать
     * половину октября с целым сентябрём нечестно: в середине месяца всегда «экономия».
     */
    val previousToDate: Money?,
    /** Средний расход в день по прошедшим дням периода. */
    val averagePerDay: Money,
    val buckets: List<Bucket>,
    /** Накопительные суммы по бакетам: текущий период (до сегодня) и прошлый (целиком). */
    val cumulative: List<Money>,
    val previousCumulative: List<Money>,
    val categories: List<CategorySlice>,
    val accounts: List<PaymentSlice>,
) {
    /** С чем сравнивать: «на это же число», если период идёт, иначе весь прошлый период. */
    val comparisonBase: Money get() = previousToDate ?: previousTotal.total

    val changePercent: Int? get() = Analytics.percentChange(total.total, comparisonBase)
}

/**
 * Агрегации в Kotlin, а не в SQL. Объём — тысячи строк за год, это миллисекунды,
 * зато логика (свёртка подкатегорий, расходы без курса, «на это же число»)
 * тестируется чистым JUnit и не размазана по SQL-запросам.
 */
object Analytics {

    fun build(
        expenses: List<Expense>,
        period: AnalyticsPeriod,
        currency: CurrencyCode,
        categories: Map<Long, Category>,
        accounts: Map<Long, Account>,
        today: LocalDate,
    ): AnalyticsReport {
        val previousPeriod = period.previous()
        val current = expenses.filter { it.localDate in period }
        val previous = expenses.filter { it.localDate in previousPeriod }

        val ongoing = today in period
        val elapsedDays = if (ongoing) ChronoUnit.DAYS.between(period.from, today) + 1 else period.days
        val previousToDate = if (ongoing) {
            val cutoff = period.sameDateInPrevious(today)
            sum(previous.filter { !it.localDate.isAfter(cutoff) }, currency)
        } else {
            null
        }

        val total = Total.of(current, currency)
        val buckets = buckets(current, period, currency)
        val previousBuckets = buckets(previous, previousPeriod, currency)
        // Текущую накопительную линию обрываем на сегодняшнем бакете — дальше будущее, а не «ноль».
        val visibleBuckets = if (ongoing) buckets.count { !it.start.isAfter(today) } else buckets.size

        return AnalyticsReport(
            period = period,
            currency = currency,
            total = total,
            previousTotal = Total.of(previous, currency),
            previousToDate = previousToDate,
            averagePerDay = divide(total.total, elapsedDays),
            buckets = buckets,
            cumulative = cumulative(buckets.take(visibleBuckets), currency),
            previousCumulative = cumulative(previousBuckets, currency),
            categories = categorySlices(current, previous, currency, categories, total.total),
            accounts = paymentSlices(current, currency, accounts, total.total),
        )
    }

    /** Все бакеты периода, включая пустые, — иначе на графике пропадут дни без трат. */
    fun buckets(expenses: List<Expense>, period: AnalyticsPeriod, currency: CurrencyCode): List<Bucket> {
        val starts = when (period.granularity) {
            Granularity.DAY -> generateSequence(period.from) { it.plusDays(1) }.takeWhile { !it.isAfter(period.to) }.toList()
            Granularity.MONTH -> generateSequence(YearMonth.from(period.from)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(period.to)) }
                .map { it.atDay(1) }
                .toList()
        }
        val byBucket = expenses.groupBy { e ->
            when (period.granularity) {
                Granularity.DAY -> e.localDate
                Granularity.MONTH -> e.localDate.withDayOfMonth(1)
            }
        }
        return starts.map { start -> Bucket(start, sum(byBucket[start].orEmpty(), currency)) }
    }

    private fun categorySlices(
        current: List<Expense>,
        previous: List<Expense>,
        currency: CurrencyCode,
        categories: Map<Long, Category>,
        total: Money,
    ): List<CategorySlice> {
        fun topLevelId(e: Expense): Long? {
            val c = categories[e.categoryId] ?: return null
            return c.parentId ?: c.id
        }
        val currentByTop = current.groupBy(::topLevelId)
        val previousByTop = previous.groupBy(::topLevelId)
        return currentByTop.map { (topId, list) ->
            val amount = sum(list, currency)
            CategorySlice(
                category = topId?.let(categories::get),
                amount = amount,
                previous = sum(previousByTop[topId].orEmpty(), currency),
                share = share(amount, total),
            )
        }.filter { it.amount.minor > 0 }.sortedByDescending { it.amount.minor }
    }

    private fun paymentSlices(
        current: List<Expense>,
        currency: CurrencyCode,
        methods: Map<Long, Account>,
        total: Money,
    ): List<PaymentSlice> = current.groupBy { it.accountId }
        .map { (id, list) ->
            val amount = sum(list, currency)
            PaymentSlice(methods[id], amount, share(amount, total))
        }
        .filter { it.amount.minor > 0 }
        .sortedByDescending { it.amount.minor }

    /** Сумма по расходам, у которых известна сумма в [currency] (без курса — пропускаются). */
    private fun sum(expenses: List<Expense>, currency: CurrencyCode): Money = Total.of(expenses, currency).total

    private fun cumulative(buckets: List<Bucket>, currency: CurrencyCode): List<Money> =
        buckets.runningFold(Money.zero(currency)) { acc, b -> acc + b.amount }.drop(1)

    private fun share(amount: Money, total: Money): Float =
        if (total.minor == 0L) 0f else (amount.minor.toDouble() / total.minor).toFloat()

    private fun divide(money: Money, days: Long): Money =
        Money.of(money.amount.divide(BigDecimal.valueOf(maxOf(days, 1)), money.currency.fractionDigits, RoundingMode.HALF_EVEN), money.currency)

    /** Изменение в процентах, округлённое до целого; null, если сравнивать не с чем. */
    fun percentChange(current: Money, previous: Money): Int? {
        if (previous.minor == 0L) return null
        return BigDecimal.valueOf(current.minor - previous.minor)
            .multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(previous.minor), 0, RoundingMode.HALF_UP)
            .toInt()
    }
}

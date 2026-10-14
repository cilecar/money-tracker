package app.moneytracker.domain.model

import java.time.LocalDate
import java.time.YearMonth

enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

data class RecurringExpense(
    val id: Long = 0,
    val amount: Money,
    val categoryId: Long,
    val accountId: Long,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val frequency: Frequency,
    /** Каждые N периодов: interval = 2 при WEEKLY — раз в две недели. */
    val interval: Int = 1,
    /**
     * Для MONTHLY/YEARLY: желаемый день месяца. Храним отдельно от дат, иначе «31-е»
     * после февраля превратилось бы в «28-е» навсегда.
     */
    val dayOfMonth: Int? = null,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val nextRunDate: LocalDate,
    val active: Boolean = true,
) {
    init {
        require(interval >= 1) { "interval must be >= 1" }
        require(dayOfMonth == null || dayOfMonth in 1..31) { "dayOfMonth must be 1..31" }
    }

    /** Следующая дата после [date] по расписанию. */
    fun nextAfter(date: LocalDate): LocalDate = when (frequency) {
        Frequency.DAILY -> date.plusDays(interval.toLong())
        Frequency.WEEKLY -> date.plusWeeks(interval.toLong())
        Frequency.MONTHLY -> clampDay(YearMonth.from(date).plusMonths(interval.toLong()))
        Frequency.YEARLY -> clampDay(YearMonth.from(date).plusYears(interval.toLong()))
    }

    /** 31-е в коротком месяце → последний день месяца; 29 февраля в невисокосный год → 28-е. */
    private fun clampDay(month: YearMonth): LocalDate {
        val day = dayOfMonth ?: startDate.dayOfMonth
        return month.atDay(minOf(day, month.lengthOfMonth()))
    }

    /** Первая дата по расписанию (от startDate) не раньше [date]. */
    fun firstOnOrAfter(date: LocalDate): LocalDate {
        var d = startDate
        while (d.isBefore(date)) d = nextAfter(d)
        return d
    }

    /** Совпадает ли расписание — от него зависит, нужно ли пересчитывать nextRunDate. */
    fun sameSchedule(other: RecurringExpense): Boolean =
        frequency == other.frequency && interval == other.interval && startDate == other.startDate

    fun isDue(today: LocalDate): Boolean =
        active && !nextRunDate.isAfter(today) && (endDate == null || !nextRunDate.isAfter(endDate))

    companion object {
        /** Новый шаблон: первая дата — дата начала, день месяца берётся из неё. */
        fun create(
            amount: Money,
            categoryId: Long,
            accountId: Long,
            frequency: Frequency,
            interval: Int,
            startDate: LocalDate,
            endDate: LocalDate? = null,
            comment: String? = null,
            tags: List<String> = emptyList(),
        ) = RecurringExpense(
            amount = amount,
            categoryId = categoryId,
            accountId = accountId,
            comment = comment?.trim()?.takeIf { it.isNotEmpty() },
            tags = Expense.normalizeTags(tags),
            frequency = frequency,
            interval = interval,
            dayOfMonth = if (frequency == Frequency.MONTHLY || frequency == Frequency.YEARLY) startDate.dayOfMonth else null,
            startDate = startDate,
            endDate = endDate,
            nextRunDate = startDate,
        )
    }
}

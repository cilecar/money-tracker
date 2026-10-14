package app.moneytracker.domain.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Шаг столбцов на графике. */
enum class Granularity { DAY, MONTH }

sealed interface AnalyticsPeriod {
    val from: LocalDate
    val to: LocalDate
    val granularity: Granularity
    fun previous(): AnalyticsPeriod
    fun next(): AnalyticsPeriod

    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1

    operator fun contains(date: LocalDate): Boolean = date in from..to

    /**
     * То же число в [previous]: 1 марта 2028 → 1 марта 2027. Календарный сдвиг, а не «N дней
     * от начала» — иначе в високосный год сравнение съезжает на день.
     */
    fun sameDateInPrevious(date: LocalDate): LocalDate

    /** Неделя с понедельника — как принято в Сербии и России (ISO-8601). */
    data class Week(val start: LocalDate) : AnalyticsPeriod {
        init {
            require(start.dayOfWeek == DayOfWeek.MONDAY) { "Week must start on Monday" }
        }
        override val from get() = start
        override val to: LocalDate get() = start.plusDays(6)
        override val granularity get() = Granularity.DAY
        override fun previous() = Week(start.minusWeeks(1))
        override fun next() = Week(start.plusWeeks(1))
        override fun sameDateInPrevious(date: LocalDate): LocalDate = date.minusWeeks(1)
    }

    data class Month(val month: YearMonth) : AnalyticsPeriod {
        override val from: LocalDate get() = month.atDay(1)
        override val to: LocalDate get() = month.atEndOfMonth()
        override val granularity get() = Granularity.DAY
        override fun previous() = Month(month.minusMonths(1))
        override fun next() = Month(month.plusMonths(1))
        /** 31 марта → 29 февраля: дальше конца прошлого месяца не уходим. */
        override fun sameDateInPrevious(date: LocalDate): LocalDate = date.minusMonths(1)
    }

    data class Year(val year: Int) : AnalyticsPeriod {
        override val from: LocalDate get() = LocalDate.of(year, 1, 1)
        override val to: LocalDate get() = LocalDate.of(year, 12, 31)
        override val granularity get() = Granularity.MONTH
        override fun previous() = Year(year - 1)
        override fun next() = Year(year + 1)
        override fun sameDateInPrevious(date: LocalDate): LocalDate = date.minusYears(1)
    }

    /** Произвольный период; «предыдущий» — такой же длины непосредственно перед ним. */
    data class Custom(override val from: LocalDate, override val to: LocalDate) : AnalyticsPeriod {
        init {
            require(!to.isBefore(from)) { "to must not be before from" }
        }
        override val granularity get() = if (days <= MAX_DAILY_DAYS) Granularity.DAY else Granularity.MONTH
        override fun previous() = Custom(from.minusDays(days), from.minusDays(1))
        override fun next() = Custom(to.plusDays(1), to.plusDays(days))
        override fun sameDateInPrevious(date: LocalDate): LocalDate = date.minusDays(days)
    }

    companion object {
        /** До двух месяцев показываем по дням, дальше столбцов слишком много — по месяцам. */
        const val MAX_DAILY_DAYS = 62

        fun weekOf(date: LocalDate) = Week(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
        fun monthOf(date: LocalDate) = Month(YearMonth.from(date))
        fun yearOf(date: LocalDate) = Year(date.year)
    }
}

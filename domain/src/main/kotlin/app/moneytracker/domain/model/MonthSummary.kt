package app.moneytracker.domain.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Блок «Осталось до конца месяца»: доходы месяца − (расходы − возвраты) и сколько можно тратить в день.
 * Переводы и корректировки сверки сюда не входят — это не доходы и не траты.
 */
data class MonthSummary(
    val month: YearMonth,
    val income: Total,
    val spent: Total,
    /** Отрицательный — перерасход. */
    val remaining: Money,
    /** Дней до конца месяца, включая сегодня; 0 — месяц прошёл. */
    val daysLeft: Long,
    /** null — тратить уже нечего (перерасход или ноль) или месяц прошёл. */
    val perDay: Money?,
) {
    val overspent: Boolean get() = remaining.minor < 0

    /** Сумма неточная: часть операций в другой валюте без курса. */
    val withoutRates: Int get() = income.withoutRates + spent.withoutRates

    companion object {
        fun of(
            month: YearMonth,
            today: LocalDate,
            currency: CurrencyCode,
            expenses: List<Expense>,
            refunds: List<Refund>,
            incomes: List<Income>,
        ): MonthSummary {
            fun inMonth(date: LocalDate) = YearMonth.from(date) == month
            val income = Total.of(incomes.filter { inMonth(it.localDate) }, currency)
            val spent = Total.spending(expenses.filter { inMonth(it.localDate) }, refunds.filter { inMonth(it.localDate) }, currency)
            val remaining = income.total - spent.total
            val daysLeft = when {
                YearMonth.from(today) == month -> ChronoUnit.DAYS.between(today, month.atEndOfMonth()) + 1
                today.isBefore(month.atDay(1)) -> month.lengthOfMonth().toLong()
                else -> 0
            }
            // Округляем вниз: если тратить ровно столько каждый день, в остаток гарантированно уложишься.
            val perDay = if (daysLeft > 0 && remaining.minor > 0) Money(remaining.minor / daysLeft, currency) else null
            return MonthSummary(month, income, spent, remaining, daysLeft, perDay)
        }
    }
}

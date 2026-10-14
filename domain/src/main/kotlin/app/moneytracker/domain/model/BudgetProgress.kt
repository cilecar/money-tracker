package app.moneytracker.domain.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class BudgetLevel(val percent: Int) {
    OK(0),
    WARNING(80),
    EXCEEDED(100),
}

data class BudgetProgress(
    val budget: Budget,
    val category: Category?,
    /** Потрачено за месяц в валюте бюджета (с подкатегориями). */
    val spent: Money,
    /** Расходы без курса в другой валюте — в [spent] не вошли. */
    val withoutRates: Int,
) {
    val limit: Money get() = budget.limit

    /** Доля 0..∞ — только для прогресс-бара. */
    val ratio: Float get() = if (limit.minor == 0L) 0f else (spent.minor.toDouble() / limit.minor).toFloat()

    /**
     * Уровень считаем в целых минорных единицах: spent * 100 >= limit * 80.
     * Через Float можно получить 79.99999% вместо 80% на круглых суммах.
     */
    val level: BudgetLevel get() = when {
        limit.minor <= 0 -> BudgetLevel.OK
        spent.minor >= limit.minor -> BudgetLevel.EXCEEDED
        spent.minor * 100 >= limit.minor * BudgetLevel.WARNING.percent -> BudgetLevel.WARNING
        else -> BudgetLevel.OK
    }

    /** Остаток (отрицательный — перерасход). */
    val remaining: Money get() = limit - spent

    /** Сколько можно тратить в день до конца месяца, чтобы уложиться (null — уже не уложиться или месяц прошёл). */
    fun dailyAllowance(month: YearMonth, today: LocalDate): Money? {
        if (YearMonth.from(today) != month || remaining.minor <= 0) return null
        val daysLeft = ChronoUnit.DAYS.between(today, month.atEndOfMonth()) + 1
        return Money(remaining.minor / daysLeft, limit.currency)
    }

    companion object {
        /**
         * Расходы месяца по бюджетам. Бюджет на категорию верхнего уровня включает
         * её подкатегории; бюджет на подкатегорию — только её.
         */
        fun compute(
            budgets: List<Budget>,
            expenses: List<Expense>,
            categories: Map<Long, Category>,
        ): List<BudgetProgress> = budgets.map { budget ->
            val ids = setOf(budget.categoryId) + categories.values.filter { it.parentId == budget.categoryId }.map { it.id }
            val total = Total.of(expenses.filter { it.categoryId in ids }, budget.limit.currency)
            BudgetProgress(budget, categories[budget.categoryId], total.total, total.withoutRates)
        }
    }
}

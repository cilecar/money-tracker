package app.moneytracker.domain.model

import java.time.LocalDate

/** Расход вместе со справочниками — всё, что нужно для строки списка. */
data class ExpenseDetails(
    val expense: Expense,
    val category: Category?,
    /** Родитель, если [category] — подкатегория. */
    val parentCategory: Category?,
    val paymentMethod: PaymentMethod?,
)

/** Итог с учётом расходов без курса: они не входят в [total], а считаются отдельно. */
data class Total(val total: Money, val withoutRates: Int) {
    companion object {
        fun of(expenses: Iterable<Expense>, currency: CurrencyCode): Total {
            var sum = Money.zero(currency)
            var missing = 0
            for (e in expenses) {
                val amount = e.amountIn(currency)
                if (amount == null) missing++ else sum += amount
            }
            return Total(sum, missing)
        }
    }
}

data class DaySection(
    val date: LocalDate,
    val total: Total,
    val items: List<ExpenseDetails>,
)

object ExpenseSections {
    /**
     * Группирует по localDate (порядок дней — от новых к старым, внутри дня — как пришло
     * из репозитория, то есть по времени, новые сверху).
     */
    fun byDay(
        expenses: List<Expense>,
        categories: Map<Long, Category>,
        paymentMethods: Map<Long, PaymentMethod>,
        currency: CurrencyCode,
    ): List<DaySection> = expenses
        .groupBy { it.localDate }
        .toSortedMap(reverseOrder())
        .map { (date, dayExpenses) ->
            DaySection(
                date = date,
                total = Total.of(dayExpenses, currency),
                items = dayExpenses.map { e ->
                    val category = categories[e.categoryId]
                    ExpenseDetails(
                        expense = e,
                        category = category,
                        parentCategory = category?.parentId?.let(categories::get),
                        paymentMethod = paymentMethods[e.paymentMethodId],
                    )
                },
            )
        }
}

package app.moneytracker.domain.model

import java.time.Instant

import java.time.LocalDate

/** Расход вместе со справочниками — всё, что нужно для строки списка. */
data class ExpenseDetails(
    val expense: Expense,
    val category: Category?,
    /** Родитель, если [category] — подкатегория. */
    val parentCategory: Category?,
    val account: Account?,
)

/** Итог с учётом операций без курса: они не входят в [total], а считаются отдельно. */
data class Total(val total: Money, val withoutRates: Int) {
    operator fun minus(other: Total) = Total(total - other.total, withoutRates + other.withoutRates)

    companion object {
        fun of(items: Iterable<RatedAmount>, currency: CurrencyCode): Total {
            var sum = Money.zero(currency)
            var missing = 0
            for (e in items) {
                val amount = e.amountIn(currency)
                if (amount == null) missing++ else sum += amount
            }
            return Total(sum, missing)
        }

        /** Траты за вычетом возвратов. */
        fun spending(expenses: Iterable<Expense>, refunds: Iterable<Refund>, currency: CurrencyCode): Total =
            of(expenses, currency) - of(refunds, currency)
    }
}

/** Строка ленты: расход, доход, возврат или перевод. */
sealed interface OperationItem {
    /** Уникален в ленте: id разных таблиц пересекаются. */
    val key: String
    val occurredAt: Instant
    val localDate: LocalDate
}

data class ExpenseItem(val details: ExpenseDetails) : OperationItem {
    override val key get() = "e-${details.expense.id}"
    override val occurredAt: Instant get() = details.expense.occurredAt
    override val localDate: LocalDate get() = details.expense.localDate
}

data class IncomeItem(val income: Income, val category: IncomeCategory?, val account: Account?) : OperationItem {
    override val key get() = "i-${income.id}"
    override val occurredAt: Instant get() = income.occurredAt
    override val localDate: LocalDate get() = income.localDate
}

data class RefundItem(
    val refund: Refund,
    val category: Category?,
    val parentCategory: Category?,
    val account: Account?,
) : OperationItem {
    override val key get() = "r-${refund.id}"
    override val occurredAt: Instant get() = refund.occurredAt
    override val localDate: LocalDate get() = refund.localDate
}

data class DaySection(
    val date: LocalDate,
    /** Траты дня за вычетом возвратов; доходы сюда не входят. */
    val total: Total,
    val items: List<OperationItem>,
)

/** Все операции, попавшие в ленту. */
data class Operations(
    val expenses: List<Expense> = emptyList(),
    val incomes: List<Income> = emptyList(),
    val refunds: List<Refund> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
) {
    val size: Int get() = expenses.size + incomes.size + refunds.size + transfers.size
}

object ExpenseSections {
    /**
     * Группирует по localDate (порядок дней — от новых к старым, внутри дня — как пришло
     * из репозитория, то есть по времени, новые сверху).
     */
    fun byDay(
        expenses: List<Expense>,
        categories: Map<Long, Category>,
        accounts: Map<Long, Account>,
        currency: CurrencyCode,
    ): List<DaySection> = byDay(Operations(expenses), categories, accounts, emptyMap(), currency)

    /** Внутри дня операции разных типов сливаются по времени, новые сверху. */
    fun byDay(
        operations: Operations,
        categories: Map<Long, Category>,
        accounts: Map<Long, Account>,
        incomeCategories: Map<Long, IncomeCategory>,
        currency: CurrencyCode,
    ): List<DaySection> {
        val items: List<OperationItem> = operations.expenses.map { e ->
            val category = categories[e.categoryId]
            ExpenseItem(ExpenseDetails(e, category, category?.parentId?.let(categories::get), accounts[e.accountId]))
        } + operations.incomes.map { IncomeItem(it, incomeCategories[it.categoryId], accounts[it.accountId]) } +
            operations.refunds.map { r ->
                val category = categories[r.categoryId]
                RefundItem(r, category, category?.parentId?.let(categories::get), accounts[r.accountId])
            } + operations.transfers.map { TransferItem(it, accounts[it.fromAccountId], accounts[it.toAccountId]) }
        return items
            .groupBy { it.localDate }
            .toSortedMap(reverseOrder())
            .map { (date, day) ->
                DaySection(
                    date = date,
                    total = Total.spending(
                        day.filterIsInstance<ExpenseItem>().map { it.details.expense },
                        day.filterIsInstance<RefundItem>().map { it.refund },
                        currency,
                    ),
                    // sortedByDescending стабильна: расходы одного времени остаются в порядке из репозитория.
                    items = day.sortedByDescending { it.occurredAt },
                )
            }
    }
}

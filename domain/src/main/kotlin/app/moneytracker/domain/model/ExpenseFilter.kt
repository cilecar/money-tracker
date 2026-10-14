package app.moneytracker.domain.model

import java.time.LocalDate

data class ExpenseFilter(
    /** Поиск по комментарию и тегам (без учёта регистра). */
    val query: String = "",
    val categoryIds: Set<Long> = emptySet(),
    val accountIds: Set<Long> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** Диапазон сумм; обе границы в одной валюте, сравнение по сумме в этой валюте. */
    val minAmount: Money? = null,
    val maxAmount: Money? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Типы операций в ленте; пусто — все. */
    val kinds: Set<OperationKind> = emptySet(),
) {
    init {
        if (minAmount != null && maxAmount != null) {
            require(minAmount.currency == maxAmount.currency) { "Amount bounds must share currency" }
        }
    }

    val isEmpty: Boolean get() = this == ExpenseFilter()

    /** Количество активных условий — для бейджа на кнопке фильтра. */
    val activeCount: Int get() = listOf(
        query.isNotBlank(),
        categoryIds.isNotEmpty(),
        accountIds.isNotEmpty(),
        tags.isNotEmpty(),
        minAmount != null || maxAmount != null,
        from != null || to != null,
        kinds.isNotEmpty(),
    ).count { it }

    fun includes(kind: OperationKind): Boolean = kinds.isEmpty() || kind in kinds

    /**
     * Доход под фильтром ленты. Категории и теги есть только у расходов: выбраны они — доходы скрываем.
     * Поиск — по комментарию.
     */
    fun matches(income: Income): Boolean = includes(OperationKind.INCOME) &&
        categoryIds.isEmpty() && tags.isEmpty() &&
        matchesCommon(income.accountId, income.localDate, income.comment, income)

    /** Возврат под фильтром: категория — та, траты которой он уменьшает (categoryIds уже с подкатегориями). */
    fun matches(refund: Refund): Boolean = includes(OperationKind.REFUND) &&
        (categoryIds.isEmpty() || refund.categoryId in categoryIds) && tags.isEmpty() &&
        matchesCommon(refund.accountId, refund.localDate, refund.comment, refund)

    private fun matchesCommon(accountId: Long, date: LocalDate, comment: String?, amount: RatedAmount): Boolean {
        if (accountIds.isNotEmpty() && accountId !in accountIds) return false
        if (from != null && date < from) return false
        if (to != null && date > to) return false
        if (query.isNotBlank() && comment?.contains(query.trim(), ignoreCase = true) != true) return false
        val bound = minAmount ?: maxAmount
        if (bound != null) {
            val value = amount.amountIn(bound.currency) ?: return false
            if (minAmount != null && value < minAmount) return false
            if (maxAmount != null && value > maxAmount) return false
        }
        return true
    }

    /** Добавляет к выбранным категориям их подкатегории. */
    fun withSubcategories(all: List<Category>): ExpenseFilter {
        if (categoryIds.isEmpty()) return this
        val children = all.filter { it.parentId in categoryIds }.map { it.id }
        return copy(categoryIds = categoryIds + children)
    }
}

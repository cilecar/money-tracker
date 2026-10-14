package app.moneytracker.domain.model

import java.time.LocalDate

data class ExpenseFilter(
    /** Поиск по комментарию и тегам (без учёта регистра). */
    val query: String = "",
    val categoryIds: Set<Long> = emptySet(),
    val paymentMethodIds: Set<Long> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** Диапазон сумм; обе границы в одной валюте, сравнение по сумме в этой валюте. */
    val minAmount: Money? = null,
    val maxAmount: Money? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
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
        paymentMethodIds.isNotEmpty(),
        tags.isNotEmpty(),
        minAmount != null || maxAmount != null,
        from != null || to != null,
    ).count { it }

    /** Добавляет к выбранным категориям их подкатегории. */
    fun withSubcategories(all: List<Category>): ExpenseFilter {
        if (categoryIds.isEmpty()) return this
        val children = all.filter { it.parentId in categoryIds }.map { it.id }
        return copy(categoryIds = categoryIds + children)
    }
}

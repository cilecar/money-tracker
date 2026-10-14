package app.moneytracker.domain.model

/** Что ссылается на категорию — от этого зависит, можно ли удалить её сразу. */
data class CategoryUsage(
    val expenses: Int = 0,
    val recurring: Int = 0,
    val budgets: Int = 0,
    val children: Int = 0,
) {
    /** Есть ли записи, которые надо куда-то перенести. Бюджет просто удалится вместе с категорией. */
    val needsTarget: Boolean get() = expenses > 0 || recurring > 0
}

data class PaymentMethodUsage(val expenses: Int = 0, val recurring: Int = 0) {
    val needsTarget: Boolean get() = expenses > 0 || recurring > 0
}

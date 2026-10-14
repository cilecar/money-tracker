package app.moneytracker.domain.model

/** Что ссылается на категорию — от этого зависит, можно ли удалить её сразу. */
data class CategoryUsage(
    val expenses: Int = 0,
    val recurring: Int = 0,
    val budgets: Int = 0,
    val children: Int = 0,
) {
    /**
     * Есть ли записи, которые надо куда-то перенести. Бюджет не переносим (у цели может быть свой,
     * а лимит «на еду» не обязательно подходит «прочему») — он удалится вместе с категорией,
     * и диалог удаления об этом предупреждает при [budgets] > 0.
     */
    val needsTarget: Boolean get() = expenses > 0 || recurring > 0
}

/**
 * [expenses] — операции, которые переносятся на другой счёт: расходы, доходы, возвраты.
 * [adjustments] — корректировки сверки: удаляются вместе со счётом, переносить их не нужно.
 * [transfers] — переводы с участием счёта: с ними счёт можно только архивировать.
 */
data class AccountUsage(val expenses: Int = 0, val recurring: Int = 0, val adjustments: Int = 0, val transfers: Int = 0) {
    val needsTarget: Boolean get() = expenses > 0 || recurring > 0
}

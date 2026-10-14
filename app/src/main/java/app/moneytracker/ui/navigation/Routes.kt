package app.moneytracker.ui.navigation

import kotlinx.serialization.Serializable

// Type-safe маршруты Navigation Compose: аргументы — поля класса, без ручной склейки строк.

@Serializable data object ExpensesRoute
@Serializable data object AnalyticsRoute
@Serializable data object BudgetsRoute
@Serializable data object MoreRoute
@Serializable data object RatesRoute

/** id = 0 — новый расход. */
@Serializable data class ExpenseEditRoute(val id: Long = 0)
@Serializable data object CategoriesRoute
@Serializable data object AccountsRoute
@Serializable data object SettingsRoute

/** id = 0 — новая категория; parentId != 0 — сразу как подкатегория. */
@Serializable data class CategoryEditRoute(val id: Long = 0, val parentId: Long = 0)

/** Список расходов с предустановленным фильтром — переход из аналитики. 0 — без ограничения. */
@Serializable
data class FilteredExpensesRoute(
    val categoryId: Long = 0,
    val accountId: Long = 0,
    val fromEpochDay: Long,
    val toEpochDay: Long,
)

@Serializable data object RecurringListRoute

/** id = 0 — новый шаблон. */
@Serializable data class RecurringEditRoute(val id: Long = 0)

@Serializable data object DataRoute

/** id = 0 — новый доход. */
@Serializable data class IncomeEditRoute(val id: Long = 0)

/** id = 0 — новый возврат; expenseId != 0 — по этому расходу. */
@Serializable data class RefundEditRoute(val id: Long = 0, val expenseId: Long = 0)

@Serializable data object IncomeCategoriesRoute

/** id = 0 — новый перевод. */
@Serializable data class TransferEditRoute(val id: Long = 0)

package app.moneytracker.ui.navigation

import kotlinx.serialization.Serializable

// Type-safe маршруты Navigation Compose: аргументы — поля класса, без ручной склейки строк.

@Serializable data object ExpensesRoute
@Serializable data object AnalyticsRoute
@Serializable data object BudgetsRoute
@Serializable data object MoreRoute
@Serializable data object RatesRoute

/**
 * id = 0 — новый расход; captureId != 0 — из покупки, захваченной в уведомлении;
 * receiptId != 0 — после сохранения прикрепить этот чек (новый расход заполняется из него).
 */
@Serializable data class ExpenseEditRoute(val id: Long = 0, val captureId: Long = 0, val receiptId: Long = 0)
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

/** «За меня заплатили»; id 0 — новая запись. */
@Serializable data class PaidForMeEditRoute(val id: Long = 0)
@Serializable data object DebtsRoute
@Serializable data class PersonDebtsRoute(val personId: Long)

/** Приём чека: text — отсканированная или присланная ссылка; receiptId != 0 — сразу к выбору расхода. */
@Serializable data class ReceiptIntakeRoute(val text: String = "", val receiptId: Long = 0)
@Serializable data class ReceiptRoute(val id: Long)
@Serializable data object PricesRoute
@Serializable data class ProductRoute(val id: Long)
@Serializable data object StoresRoute

/** filter — имя ReceiptFilter (ALL, PENDING, FAILED, UNATTACHED). */
@Serializable data class ReceiptsRoute(val filter: String = "ALL")

@Serializable data object AutoCaptureRoute
@Serializable data object PendingCapturesRoute
@Serializable data object CaptureDebugRoute

package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import app.moneytracker.data.db.entity.BudgetAlertEntity
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.entity.RecurringExpenseEntity

/** Операции над всей базой для импорта/экспорта. */
@Dao
abstract class MaintenanceDao {
    @Query("SELECT * FROM recurring_expense") abstract suspend fun allRecurring(): List<RecurringExpenseEntity>
    @Query("SELECT * FROM exchange_rate") abstract suspend fun allRates(): List<ExchangeRateEntity>
    @Query("SELECT * FROM budget_alert") abstract suspend fun allBudgetAlerts(): List<BudgetAlertEntity>

    // Порядок важен из-за внешних ключей: сначала ссылающиеся таблицы, потом справочники.
    @Query("DELETE FROM expense_tag") abstract suspend fun deleteExpenseTags()
    @Query("DELETE FROM expense") abstract suspend fun deleteExpenses()
    @Query("DELETE FROM tag") abstract suspend fun deleteTags()
    @Query("DELETE FROM budget_alert") abstract suspend fun deleteBudgetAlerts()
    @Query("DELETE FROM budget") abstract suspend fun deleteBudgets()
    @Query("DELETE FROM recurring_expense") abstract suspend fun deleteRecurring()
    @Query("DELETE FROM category WHERE parent_id IS NOT NULL") abstract suspend fun deleteSubcategories()
    @Query("DELETE FROM category") abstract suspend fun deleteCategories()
    @Query("DELETE FROM payment_method") abstract suspend fun deletePaymentMethods()
    @Query("DELETE FROM exchange_rate") abstract suspend fun deleteRates()

    open suspend fun deleteAll() {
        deleteExpenseTags()
        deleteExpenses()
        deleteTags()
        deleteBudgetAlerts()
        deleteBudgets()
        deleteRecurring()
        deleteSubcategories()
        deleteCategories()
        deletePaymentMethods()
        deleteRates()
    }
}

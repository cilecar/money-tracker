package app.moneytracker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import app.moneytracker.data.db.dao.BudgetDao
import app.moneytracker.data.db.dao.CategoryDao
import app.moneytracker.data.db.dao.ExchangeRateDao
import app.moneytracker.data.db.dao.ExpenseDao
import app.moneytracker.data.db.dao.MaintenanceDao
import app.moneytracker.data.db.dao.PaymentMethodDao
import app.moneytracker.data.db.dao.RecurringDao
import app.moneytracker.data.db.entity.BudgetAlertEntity
import app.moneytracker.data.db.entity.BudgetEntity
import app.moneytracker.data.db.entity.CategoryEntity
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.entity.ExpenseEntity
import app.moneytracker.data.db.entity.ExpenseTagCrossRef
import app.moneytracker.data.db.entity.PaymentMethodEntity
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import app.moneytracker.data.db.entity.TagEntity

@Database(
    entities = [
        ExpenseEntity::class,
        CategoryEntity::class,
        PaymentMethodEntity::class,
        TagEntity::class,
        ExpenseTagCrossRef::class,
        ExchangeRateEntity::class,
        BudgetEntity::class,
        BudgetAlertEntity::class,
        RecurringExpenseEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun paymentMethodDao(): PaymentMethodDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "money_tracker.db"
        const val VERSION = 1

        /**
         * Миграции схемы, регистрируются в DataModule. Новая версия базы = миграция сюда
         * (MigrationTest прогонит её по экспортированным схемам из app/schemas).
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        /** Есть ли цепочка миграций from → to. Нужна, чтобы не принять резервную копию, которую Room не откроет. */
        fun hasMigrationPath(from: Int, to: Int): Boolean {
            var version = from
            while (version < to) {
                version = MIGRATIONS.filter { it.startVersion == version && it.endVersion <= to }.maxOfOrNull { it.endVersion } ?: return false
            }
            return version == to
        }
    }
}

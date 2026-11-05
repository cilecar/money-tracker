package app.moneytracker.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import app.moneytracker.data.db.dao.BudgetDao
import app.moneytracker.data.db.dao.CaptureDao
import app.moneytracker.data.db.dao.CategoryDao
import app.moneytracker.data.db.dao.ExchangeRateDao
import app.moneytracker.data.db.dao.DebtDao
import app.moneytracker.data.db.dao.ExpenseDao
import app.moneytracker.data.db.dao.PersonDao
import app.moneytracker.data.db.dao.IncomeCategoryDao
import app.moneytracker.data.db.dao.IncomeDao
import app.moneytracker.data.db.dao.RefundDao
import app.moneytracker.data.db.dao.TransferDao
import app.moneytracker.data.db.dao.MaintenanceDao
import app.moneytracker.data.db.dao.AccountDao
import app.moneytracker.data.db.dao.RecurringDao
import app.moneytracker.data.db.dao.ReceiptDao
import app.moneytracker.data.db.entity.ArticleEntity
import app.moneytracker.data.db.entity.ChainEntity
import app.moneytracker.data.db.entity.ProductEntity
import app.moneytracker.data.db.entity.ReceiptEntity
import app.moneytracker.data.db.entity.ReceiptItemEntity
import app.moneytracker.data.db.entity.StoreEntity
import app.moneytracker.data.db.entity.BalanceAdjustmentEntity
import app.moneytracker.data.db.entity.BudgetAlertEntity
import app.moneytracker.data.db.entity.BudgetEntity
import app.moneytracker.data.db.entity.CategoryEntity
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.entity.ExpenseEntity
import app.moneytracker.data.db.entity.ExpenseShareEntity
import app.moneytracker.data.db.entity.PaidForMeEntity
import app.moneytracker.data.db.entity.PersonEntity
import app.moneytracker.data.db.entity.ExpenseTagCrossRef
import app.moneytracker.data.db.entity.IncomeCategoryEntity
import app.moneytracker.data.db.entity.IncomeEntity
import app.moneytracker.data.db.entity.RefundEntity
import app.moneytracker.data.db.entity.TransferEntity
import app.moneytracker.data.db.entity.CapturedPurchaseEntity
import app.moneytracker.data.db.entity.MerchantRuleEntity
import app.moneytracker.data.db.entity.AccountEntity
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import app.moneytracker.data.db.entity.TagEntity

@Database(
    entities = [
        ExpenseEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        TagEntity::class,
        ExpenseTagCrossRef::class,
        ExchangeRateEntity::class,
        BudgetEntity::class,
        BudgetAlertEntity::class,
        RecurringExpenseEntity::class,
        BalanceAdjustmentEntity::class,
        IncomeCategoryEntity::class,
        IncomeEntity::class,
        RefundEntity::class,
        TransferEntity::class,
        CapturedPurchaseEntity::class,
        MerchantRuleEntity::class,
        PersonEntity::class,
        ExpenseShareEntity::class,
        PaidForMeEntity::class,
        ChainEntity::class,
        StoreEntity::class,
        ReceiptEntity::class,
        ProductEntity::class,
        ArticleEntity::class,
        ReceiptItemEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun incomeDao(): IncomeDao
    abstract fun refundDao(): RefundDao
    abstract fun incomeCategoryDao(): IncomeCategoryDao
    abstract fun transferDao(): TransferDao
    abstract fun captureDao(): CaptureDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun budgetDao(): BudgetDao
    abstract fun recurringDao(): RecurringDao
    abstract fun maintenanceDao(): MaintenanceDao
    abstract fun personDao(): PersonDao
    abstract fun debtDao(): DebtDao
    abstract fun receiptDao(): ReceiptDao

    companion object {
        const val NAME = "money_tracker.db"
        const val VERSION = 6

        /**
         * Миграции схемы, регистрируются в DataModule. Новая версия базы = миграция сюда
         * (MigrationTest прогонит её по экспортированным схемам из app/schemas).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(Migration1To2, Migration2To3, Migration3To4, Migration4To5, Migration5To6)

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

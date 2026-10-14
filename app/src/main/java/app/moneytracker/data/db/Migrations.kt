package app.moneytracker.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

/**
 * 1 → 2: способы оплаты становятся счетами (валюта, начальный остаток), плюс таблицы доходов, возвратов,
 * переводов и корректировок сверки.
 *
 * SQLite не умеет менять колонки с внешними ключами, поэтому account, expense и recurring_expense
 * пересоздаются: новая таблица → копия строк → удаление старой → переименование. Room выполняет миграцию
 * в транзакции, а внешние ключи включает только после неё (в onOpen), так что промежуточные ссылки
 * на удалённую таблицу не мешают; в конце foreign_key_check проверяет, что все ссылки целы.
 *
 * SQL таблиц — дословно из app/schemas/…/2.json: Room после миграции сверяет схему с ожидаемой.
 */
object Migration1To2 : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        // Начальная дата счёта без расходов.
        createAccount(db, LocalDate.now())
        recreateRecurring(db)
        recreateExpense(db)
        db.execSQL("DROP TABLE payment_method")
        createNewTables(db)
        seedFeesCategory(db)
        seedIncomeCategories(db)
        db.query("PRAGMA foreign_key_check").use { c ->
            check(!c.moveToFirst()) { "Migration 1→2 broke foreign key in table ${c.getString(0)}" }
        }
    }

    /**
     * Валюта счёта: прежняя валюта способа оплаты; если её не было (наличные) — самая частая валюта его расходов,
     * при равенстве та, что встречалась позже; затем валюта регулярных платежей; иначе RSD.
     * Начальный остаток 0 на дату самого раннего расхода: вся история входит в баланс, и одна сверка
     * приводит его к факту.
     */
    private fun createAccount(db: SupportSQLiteDatabase, today: LocalDate) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `account` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, `currency` TEXT NOT NULL, `opening_balance_minor` INTEGER NOT NULL, " +
                "`opening_date` INTEGER NOT NULL, `default_key` TEXT, `sort_order` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
        )
        db.execSQL(
            """
            INSERT INTO account (id, name, type, currency, opening_balance_minor, opening_date, default_key, sort_order, archived)
            SELECT p.id, p.name, p.type,
                COALESCE(
                    p.currency,
                    (SELECT e.currency FROM expense e WHERE e.payment_method_id = p.id
                        GROUP BY e.currency ORDER BY COUNT(*) DESC, MAX(e.local_date) DESC LIMIT 1),
                    (SELECT r.currency FROM recurring_expense r WHERE r.payment_method_id = p.id
                        GROUP BY r.currency ORDER BY COUNT(*) DESC LIMIT 1),
                    'RSD'
                ),
                0,
                COALESCE((SELECT MIN(e.local_date) FROM expense e WHERE e.payment_method_id = p.id), ?),
                p.default_key, p.sort_order, p.archived
            FROM payment_method p
            """.trimIndent(),
            arrayOf<Any>(today.toEpochDay()),
        )
    }

    private fun recreateRecurring(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recurring_expense_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `category_id` INTEGER NOT NULL, " +
                "`account_id` INTEGER NOT NULL, `comment` TEXT, `tags` TEXT NOT NULL, `frequency` TEXT NOT NULL, " +
                "`interval` INTEGER NOT NULL, `day_of_month` INTEGER, `start_date` INTEGER NOT NULL, `end_date` INTEGER, " +
                "`next_run_date` INTEGER NOT NULL, `active` INTEGER NOT NULL, " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL(
            "INSERT INTO recurring_expense_new (id, amount_minor, currency, category_id, account_id, comment, tags, frequency, " +
                "`interval`, day_of_month, start_date, end_date, next_run_date, active) " +
                "SELECT id, amount_minor, currency, category_id, payment_method_id, comment, tags, frequency, " +
                "`interval`, day_of_month, start_date, end_date, next_run_date, active FROM recurring_expense",
        )
        db.execSQL("DROP TABLE recurring_expense")
        db.execSQL("ALTER TABLE recurring_expense_new RENAME TO recurring_expense")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_expense_category_id` ON `recurring_expense` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_expense_account_id` ON `recurring_expense` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recurring_expense_next_run_date` ON `recurring_expense` (`next_run_date`)")
    }

    /** expense_tag ссылается на expense по имени таблицы, после переименования ссылки снова верны. */
    private fun recreateExpense(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `expense_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `category_id` INTEGER NOT NULL, " +
                "`account_id` INTEGER NOT NULL, `occurred_at` INTEGER NOT NULL, `local_date` INTEGER NOT NULL, `comment` TEXT, " +
                "`rate_rsd_per_eur` TEXT, `rate_rub_per_eur` TEXT, `rate_date` INTEGER, `rsd_minor` INTEGER, " +
                "`eur_minor` INTEGER, `rub_minor` INTEGER, `recurring_id` INTEGER, `account_amount_minor` INTEGER, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`recurring_id`) REFERENCES `recurring_expense`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "INSERT INTO expense_new (id, amount_minor, currency, category_id, account_id, occurred_at, local_date, comment, " +
                "rate_rsd_per_eur, rate_rub_per_eur, rate_date, rsd_minor, eur_minor, rub_minor, recurring_id, " +
                "account_amount_minor, created_at, updated_at) " +
                "SELECT id, amount_minor, currency, category_id, payment_method_id, occurred_at, local_date, comment, " +
                "rate_rsd_per_eur, rate_rub_per_eur, rate_date, rsd_minor, eur_minor, rub_minor, recurring_id, " +
                "NULL, created_at, updated_at FROM expense",
        )
        db.execSQL("DROP TABLE expense")
        db.execSQL("ALTER TABLE expense_new RENAME TO expense")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_local_date` ON `expense` (`local_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_category_id` ON `expense` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_account_id` ON `expense` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_recurring_id` ON `expense` (`recurring_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_rate_date` ON `expense` (`rate_date`)")
    }

    private fun createNewTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `balance_adjustment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`account_id` INTEGER NOT NULL, `amount_minor` INTEGER NOT NULL, `occurred_at` INTEGER NOT NULL, " +
                "`local_date` INTEGER NOT NULL, `comment` TEXT, `created_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_balance_adjustment_account_id` ON `balance_adjustment` (`account_id`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `income_category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `default_key` TEXT, `icon_key` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                "`sort_order` INTEGER NOT NULL, `archived` INTEGER NOT NULL)",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `income` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `account_id` INTEGER NOT NULL, " +
                "`income_category_id` INTEGER NOT NULL, `occurred_at` INTEGER NOT NULL, `local_date` INTEGER NOT NULL, " +
                "`comment` TEXT, `rate_rsd_per_eur` TEXT, `rate_rub_per_eur` TEXT, `rate_date` INTEGER, " +
                "`rsd_minor` INTEGER, `eur_minor` INTEGER, `rub_minor` INTEGER, `account_amount_minor` INTEGER, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`income_category_id`) REFERENCES `income_category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_local_date` ON `income` (`local_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_account_id` ON `income` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_income_category_id` ON `income` (`income_category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_income_rate_date` ON `income` (`rate_date`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `refund` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`amount_minor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `account_id` INTEGER NOT NULL, " +
                "`expense_id` INTEGER, `category_id` INTEGER NOT NULL, `occurred_at` INTEGER NOT NULL, " +
                "`local_date` INTEGER NOT NULL, `comment` TEXT, `rate_rsd_per_eur` TEXT, `rate_rub_per_eur` TEXT, " +
                "`rate_date` INTEGER, `rsd_minor` INTEGER, `eur_minor` INTEGER, `rub_minor` INTEGER, " +
                "`account_amount_minor` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`expense_id`) REFERENCES `expense`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_local_date` ON `refund` (`local_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_account_id` ON `refund` (`account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_expense_id` ON `refund` (`expense_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_category_id` ON `refund` (`category_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_refund_rate_date` ON `refund` (`rate_date`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transfer` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`from_account_id` INTEGER NOT NULL, `to_account_id` INTEGER NOT NULL, `from_amount_minor` INTEGER NOT NULL, " +
                "`from_currency` TEXT NOT NULL, `to_amount_minor` INTEGER NOT NULL, `to_currency` TEXT NOT NULL, " +
                "`fee_expense_id` INTEGER, `occurred_at` INTEGER NOT NULL, `local_date` INTEGER NOT NULL, `comment` TEXT, " +
                "`rate_rsd_per_eur` TEXT, `rate_rub_per_eur` TEXT, `rate_date` INTEGER, " +
                "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`from_account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`to_account_id`) REFERENCES `account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                "FOREIGN KEY(`fee_expense_id`) REFERENCES `expense`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_local_date` ON `transfer` (`local_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_from_account_id` ON `transfer` (`from_account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_to_account_id` ON `transfer` (`to_account_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_fee_expense_id` ON `transfer` (`fee_expense_id`)")
    }

    /**
     * Категория «Комиссии» для комиссий переводов. Если у пользователя уже есть своя с таким именем,
     * берём её (вторая с тем же именем нарушила бы уникальность имён), иначе добавляем в конец списка.
     * Имя в базе — запасное: UI показывает перевод по default_key.
     */
    private fun seedFeesCategory(db: SupportSQLiteDatabase) {
        val fees = DefaultData.FEES
        val existing = db.query(
            "SELECT id FROM category WHERE parent_id IS NULL AND archived = 0 AND default_key IS NULL " +
                "AND (name IN ('Комиссии', 'комиссии', 'КОМИССИИ') OR lower(name) = 'fees') LIMIT 1",
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (db.query("SELECT 1 FROM category WHERE default_key = ?", arrayOf(fees.key)).use { it.moveToFirst() }) return
        if (existing != null) {
            db.execSQL("UPDATE category SET default_key = ? WHERE id = ?", arrayOf<Any>(fees.key, existing))
        } else {
            db.execSQL(
                "INSERT INTO category (name, default_key, icon_key, color, parent_id, sort_order, archived) " +
                    "SELECT ?, ?, ?, ?, NULL, COALESCE(MAX(sort_order), -1) + 1, 0 FROM category WHERE parent_id IS NULL",
                arrayOf<Any>("Комиссии", fees.key, fees.iconKey, fees.color),
            )
        }
    }

    private fun seedIncomeCategories(db: SupportSQLiteDatabase) {
        DefaultData.incomeCategories.forEachIndexed { index, c ->
            db.execSQL(
                "INSERT INTO income_category (name, default_key, icon_key, color, sort_order, archived) VALUES (?, ?, ?, ?, ?, 0)",
                arrayOf<Any>(c.fallbackName, c.key, c.iconKey, c.color, index),
            )
        }
    }
}

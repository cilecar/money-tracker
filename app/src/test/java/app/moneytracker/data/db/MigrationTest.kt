package app.moneytracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * Каждая прежняя версия схемы (app/schemas) доводится миграциями до текущей, и Room её открывает.
 * Без этого резервная копия старой версии прошла бы проверку и уронила бы приложение после восстановления.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun everyVersionMigratesToLatest() {
        for (from in 1..AppDatabase.VERSION) {
            val name = "migration-from-$from.db"
            helper.createDatabase(name, from).close()
            helper.runMigrationsAndValidate(name, AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).close()

            // Room сверяет identity hash с текущими сущностями — схема в файле та, что ждёт приложение.
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*AppDatabase.MIGRATIONS)
                .allowMainThreadQueries()
                .build()
            try {
                db.openHelper.writableDatabase.query("SELECT count(*) FROM expense").use { assertTrue(it.moveToFirst()) }
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }
    }

    /**
     * 1→2 на базе с данными: способы оплаты становятся счетами, ни одна строка не теряется,
     * валюта выводится из расходов, а баланс сразу считается по всей истории.
     */
    @Test
    fun migration1To2KeepsDataAndDerivesAccounts() = runTest {
        val name = "migration-1-2-data.db"
        helper.createDatabase(name, 1).use { db -> fillVersion1(db) }

        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).use { db ->
            fun longs(sql: String) = db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            fun strings(sql: String) = db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

            // Валюта: наличные — самая частая валюта расходов (RSD), карта — своя, «Рубли» — по регулярному, пустой — RSD.
            assertEquals(listOf("RSD", "EUR", "RUB", "RSD"), strings("SELECT currency FROM account ORDER BY id"))
            assertEquals(listOf("Наличные", "Карта", "Рубли", "Старый"), strings("SELECT name FROM account ORDER BY id"))
            assertEquals(listOf("cash"), strings("SELECT default_key FROM account WHERE default_key IS NOT NULL"))
            assertEquals(listOf(0L, 0L, 0L, 0L), longs("SELECT opening_balance_minor FROM account ORDER BY id"))
            // Начальная дата — самый ранний расход счёта, без расходов — сегодня.
            val today = LocalDate.now().toEpochDay()
            assertEquals(listOf(20050L, 20300L, today, today), longs("SELECT opening_date FROM account ORDER BY id"))
            assertEquals(listOf(1L), longs("SELECT archived FROM account WHERE id = 4"))

            // Ни одна строка не потеряна, ссылки перенесены как были.
            assertEquals(listOf(1L, 1L, 1L, 2L, 2L), longs("SELECT account_id FROM expense ORDER BY id"))
            assertEquals(listOf(100000L, 50000L, 1000L, 117000L, 50000L), longs("SELECT amount_minor FROM expense ORDER BY id"))
            assertEquals(listOf(1L), longs("SELECT recurring_id FROM expense WHERE recurring_id IS NOT NULL"))
            assertEquals(5, longs("SELECT COUNT(*) FROM expense WHERE account_amount_minor IS NULL").single().toInt())
            assertEquals(listOf(3L), longs("SELECT account_id FROM recurring_expense"))
            assertEquals(listOf(1L), longs("SELECT expense_id FROM expense_tag"))
            assertEquals(listOf(1L), longs("SELECT category_id FROM budget"))

            // Новые справочники: «Комиссии» в конце списка верхнего уровня, четыре категории доходов.
            assertEquals(listOf("Комиссии"), strings("SELECT name FROM category WHERE default_key = 'fees'"))
            assertEquals(listOf(1L), longs("SELECT sort_order FROM category WHERE default_key = 'fees'"))
            assertEquals(
                listOf("salary", "freelance", "gift", "income_other"),
                strings("SELECT default_key FROM income_category ORDER BY sort_order"),
            )
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val balances = room.accountDao().observeBalances().first().associateBy { it.accountId }
            // Наличные RSD: 1000 + 500 RSD и 10 € по снапшоту (1170 RSD).
            assertEquals(-(100000L + 50000L + 117000L), balances.getValue(1).balanceMinor)
            assertEquals(0, balances.getValue(1).unconverted)
            // Карта EUR: 1170 RSD = 10 € по снапшоту; расход в RUB без курса не вошёл.
            assertEquals(-1000L, balances.getValue(2).balanceMinor)
            assertEquals(1, balances.getValue(2).unconverted)
            assertEquals(0L, balances.getValue(4).balanceMinor)
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    /** Своя категория «Комиссии» не дублируется: ей присваивается ключ. */
    @Test
    fun migration1To2ReusesExistingFeesCategory() {
        val name = "migration-1-2-fees.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO category VALUES (1, 'Еда', 'food', 'restaurant', 1, NULL, 0, 0)")
            db.execSQL("INSERT INTO category VALUES (7, 'Комиссии', NULL, 'receipt', 1, NULL, 1, 0)")
            db.execSQL("INSERT INTO payment_method VALUES (1, 'Наличные', 'CASH', NULL, 'cash', 0, 0)")
        }
        helper.runMigrationsAndValidate(name, 2, true, Migration1To2).use { db ->
            db.query("SELECT id FROM category WHERE default_key = 'fees'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7L, c.getLong(0))
                assertFalse(c.moveToNext())
            }
            db.query("SELECT COUNT(*) FROM category").use { c -> c.moveToFirst(); assertEquals(2, c.getInt(0)) }
        }
        context.deleteDatabase(name)
    }

    /**
     * 2→3 на базе с данными: у расходов, доходов, возвратов и переводов появляется пустой курс USDT,
     * остальные колонки и балансы не меняются, а старый снапшот читается без USDT.
     */
    @Test
    fun migration2To3KeepsDataAndAddsEmptyUsdtRate() = runTest {
        val name = "migration-2-3-data.db"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO category (id, name, default_key, icon_key, color, parent_id, sort_order, archived) VALUES (1, 'Еда', 'food', 'restaurant', 1, NULL, 0, 0)")
            db.execSQL("INSERT INTO income_category (id, name, default_key, icon_key, color, sort_order, archived) VALUES (1, 'Зарплата', 'salary', 'work', 1, 0, 0)")
            db.execSQL("INSERT INTO account (id, name, type, currency, opening_balance_minor, opening_date, default_key, sort_order, archived) VALUES (1, 'Карта', 'CARD', 'EUR', 10000, 20300, NULL, 0, 0)")
            db.execSQL("INSERT INTO account (id, name, type, currency, opening_balance_minor, opening_date, default_key, sort_order, archived) VALUES (2, 'Наличные', 'CASH', 'RSD', 0, 20300, NULL, 1, 0)")
            val rated = "rate_rsd_per_eur, rate_rub_per_eur, rate_date, rsd_minor, eur_minor, rub_minor"
            db.execSQL("INSERT INTO expense (id, amount_minor, currency, category_id, account_id, occurred_at, local_date, $rated, created_at, updated_at) VALUES (1, 117000, 'RSD', 1, 1, 0, 20301, '117', '95', 20301, 117000, 1000, 9500, 0, 0)")
            db.execSQL("INSERT INTO income (id, amount_minor, currency, account_id, income_category_id, occurred_at, local_date, $rated, account_amount_minor, created_at, updated_at) VALUES (1, 5000, 'EUR', 1, 1, 0, 20302, '117', '95', 20302, 585000, 5000, 47500, NULL, 0, 0)")
            db.execSQL("INSERT INTO refund (id, amount_minor, currency, account_id, expense_id, category_id, occurred_at, local_date, $rated, created_at, updated_at) VALUES (1, 200, 'EUR', 1, 1, 1, 0, 20303, '117', '95', 20303, 23400, 200, 1900, 0, 0)")
            db.execSQL("INSERT INTO transfer (id, from_account_id, to_account_id, from_amount_minor, from_currency, to_amount_minor, to_currency, occurred_at, local_date, rate_rsd_per_eur, rate_rub_per_eur, rate_date, created_at, updated_at) VALUES (1, 1, 2, 2000, 'EUR', 233000, 'RSD', 0, 20304, '117', '95', 20304, 0, 0)")
        }

        helper.runMigrationsAndValidate(name, 3, true, Migration2To3).use { db ->
            fun longs(sql: String) = db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
            for (table in listOf("expense", "income", "refund", "transfer")) {
                assertEquals(table, listOf(1L), longs("SELECT COUNT(*) FROM $table WHERE rate_usdt_per_eur IS NULL"))
                assertEquals(table, listOf(1L), longs("SELECT COUNT(*) FROM $table WHERE rate_rsd_per_eur = '117' AND rate_date IS NOT NULL"))
            }
            assertEquals(listOf(117000L), longs("SELECT rsd_minor FROM expense"))
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val expense = room.expenseDao().getAll().single().toDomain()
            assertEquals(java.math.BigDecimal("117"), expense.rates!!.rsdPerEur)
            assertNull(expense.rates!!.usdtPerEur)
            assertNull(room.transferDao().getAll().single().toDomain().apiRates!!.usdtPerEur)
            // Карта EUR: 100 − 10 (1170 RSD) + 50 + 2 − 20 = 122 €; наличные: +2 330 RSD.
            val balances = room.accountDao().observeBalances().first().associateBy { it.accountId }
            assertEquals(12200L, balances.getValue(1).balanceMinor)
            assertEquals(233000L, balances.getValue(2).balanceMinor)
        } finally {
            room.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationPathCoversEveryOlderVersion() {
        for (from in 1..AppDatabase.VERSION) assertTrue("no path $from → ${AppDatabase.VERSION}", AppDatabase.hasMigrationPath(from, AppDatabase.VERSION))
        assertFalse(AppDatabase.hasMigrationPath(0, AppDatabase.VERSION))
    }

    private fun fillVersion1(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO category VALUES (1, 'Еда', 'food', 'restaurant', 1, NULL, 0, 0)")
        db.execSQL("INSERT INTO category VALUES (2, 'Кафе', NULL, 'local_cafe', 1, 1, 0, 0)")
        // Наличные без валюты: чаще всего платили в RSD (два раза против одного в EUR).
        db.execSQL("INSERT INTO payment_method VALUES (1, 'Наличные', 'CASH', NULL, 'cash', 0, 0)")
        db.execSQL("INSERT INTO payment_method VALUES (2, 'Карта', 'CARD', 'EUR', NULL, 1, 0)")
        // Без расходов, но с регулярным платежом в RUB.
        db.execSQL("INSERT INTO payment_method VALUES (3, 'Рубли', 'TRANSFER', NULL, NULL, 2, 0)")
        // Пустой архивный.
        db.execSQL("INSERT INTO payment_method VALUES (4, 'Старый', 'CARD', NULL, NULL, 3, 1)")
        db.execSQL("INSERT INTO recurring_expense VALUES (1, 100000, 'RUB', 1, 3, NULL, '[]', 'MONTHLY', 1, 5, 20000, NULL, 20400, 1)")

        fun expense(id: Long, minor: Long, currency: String, method: Long, day: Long, converted: Triple<Long, Long, Long>?, recurring: Long? = null) {
            val rates: Array<Any?> = if (converted == null) arrayOf(null, null, null) else arrayOf("117", "95", day)
            db.execSQL(
                "INSERT INTO expense VALUES (?, ?, ?, 1, ?, 0, ?, NULL, ?, ?, ?, ?, ?, ?, ?, 0, 0)",
                arrayOf(id, minor, currency, method, day, *rates, converted?.first, converted?.second, converted?.third, recurring),
            )
        }
        expense(1, 100000, "RSD", 1, day = 20100, converted = Triple(100000, 854, 81500))
        expense(2, 50000, "RSD", 1, day = 20200, converted = Triple(50000, 427, 40700))
        expense(3, 1000, "EUR", 1, day = 20050, converted = Triple(117000, 1000, 9500), recurring = 1)
        expense(4, 117000, "RSD", 2, day = 20300, converted = Triple(117000, 1000, 9500))
        expense(5, 50000, "RUB", 2, day = 20301, converted = null)
        db.execSQL("INSERT INTO tag VALUES (1, 'Отпуск', 'отпуск')")
        db.execSQL("INSERT INTO expense_tag VALUES (1, 1)")
        db.execSQL("INSERT INTO budget VALUES (1, 1, 5000000, 'RSD')")
    }
}

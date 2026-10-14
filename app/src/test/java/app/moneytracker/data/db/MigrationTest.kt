package app.moneytracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    @Test
    fun migrationPathCoversEveryOlderVersion() {
        for (from in 1..AppDatabase.VERSION) assertTrue("no path $from → ${AppDatabase.VERSION}", AppDatabase.hasMigrationPath(from, AppDatabase.VERSION))
        assertFalse(AppDatabase.hasMigrationPath(0, AppDatabase.VERSION))
    }
}

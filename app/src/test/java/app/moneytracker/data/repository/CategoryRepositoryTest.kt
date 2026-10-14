package app.moneytracker.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var categories: CategoryRepositoryImpl
    private lateinit var methods: AccountRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl
    private val now = Instant.parse("2026-10-14T10:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        categories = CategoryRepositoryImpl(db.categoryDao())
        methods = AccountRepositoryImpl(db.accountDao())
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addExpense(categoryId: Long, methodId: Long = 1) = expenses.insert(
        Expense.create(Money(100, CurrencyCode.EUR), categoryId, methodId, now, ZoneOffset.UTC, null, now),
    )

    @Test
    fun usageAndDeleteWithMove() = runTest {
        val e1 = addExpense(3)
        addExpense(3)
        assertEquals(2, categories.usage(3).expenses)

        categories.delete(3, moveTo = 1)

        assertNull(categories.get(3))
        assertEquals(1L, expenses.get(e1)!!.categoryId)
        assertEquals(2, categories.usage(1).expenses)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun databaseRefusesToOrphanExpenses() = runTest {
        // Страховка на уровне БД: FK RESTRICT не даст удалить категорию с расходами без переноса.
        addExpense(3)
        categories.delete(3, moveTo = null)
    }

    @Test
    fun subcategoriesAndSortOrder() = runTest {
        val sub = categories.upsert(Category(name = "Кафе", iconKey = "local_cafe", color = 0, parentId = 1, sortOrder = categories.nextSortOrder(1)))
        assertEquals(1, categories.usage(1).children)
        assertEquals(0, categories.get(sub)!!.sortOrder)
        assertEquals(11, categories.nextSortOrder(null)) // 11 категорий по умолчанию: 0..10
        assertEquals(1, categories.nextSortOrder(1))
    }

    @Test
    fun archiveSeveral() = runTest {
        categories.setArchived(listOf(1, 2), true)
        assertEquals(9, categories.getAll(includeArchived = false).size)
        assertEquals(11, categories.getAll(includeArchived = true).size)
    }

    @Test
    fun accountDeleteWithMove() = runTest {
        val card = methods.upsert(app.moneytracker.domain.model.Account(name = "Карта", type = app.moneytracker.domain.model.AccountType.CARD, currency = CurrencyCode.RUB, openingDate = java.time.LocalDate.of(2026, 1, 1)))
        val e = addExpense(1, methodId = card)
        assertEquals(1, methods.usage(card).expenses)
        methods.delete(card, moveTo = 1)
        assertEquals(1L, expenses.get(e)!!.accountId)
    }

    /** Возврат ссылается на категорию (RESTRICT): без переноса удаление упало бы на внешнем ключе. */
    @Test
    fun deleteWithMoveCarriesRefunds() = runTest {
        val refunds = RefundRepositoryImpl(db.refundDao())
        val r = refunds.insert(
            app.moneytracker.domain.model.Refund.create(
                app.moneytracker.domain.model.Money(100, CurrencyCode.RSD), 1, null, 2, java.time.Instant.parse("2026-10-01T10:00:00Z"),
                java.time.ZoneOffset.UTC, null, java.time.Instant.parse("2026-10-01T10:00:00Z"),
            ),
        )
        assertTrue(categories.usage(2).needsTarget)
        categories.delete(2, moveTo = 3)
        assertEquals(3L, refunds.get(r)!!.categoryId)
    }
}

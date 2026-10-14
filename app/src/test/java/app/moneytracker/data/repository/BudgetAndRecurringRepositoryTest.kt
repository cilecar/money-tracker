package app.moneytracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.domain.model.Budget
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RecurringExpense
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class BudgetAndRecurringRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var budgets: BudgetRepositoryImpl
    private lateinit var recurring: RecurringRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl
    private val oct = YearMonth.of(2026, 10)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        budgets = BudgetRepositoryImpl(db.budgetDao())
        recurring = RecurringRepositoryImpl(db, db.recurringDao())
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun onePerCategoryAndAlertsSurviveLimitChange() = runTest {
        val id = budgets.upsert(Budget(categoryId = 1, limit = Money(1000, CurrencyCode.EUR)))
        assertTrue(budgets.markAlerted(id, oct, BudgetLevel.WARNING))
        assertFalse(budgets.markAlerted(id, oct, BudgetLevel.WARNING))

        // Повторное сохранение для той же категории — обновление, а не второй бюджет
        val again = budgets.upsert(Budget(categoryId = 1, limit = Money(2000, CurrencyCode.RSD)))
        assertEquals(id, again)
        assertEquals(1, budgets.getAll().size)
        assertEquals(Money(2000, CurrencyCode.RSD), budgets.getAll().single().limit)
        // Отметка не потерялась (UPDATE, а не REPLACE с каскадным удалением)
        assertFalse(budgets.markAlerted(id, oct, BudgetLevel.WARNING))
        assertTrue(budgets.markAlerted(id, oct.plusMonths(1), BudgetLevel.WARNING))
    }

    @Test
    fun recordOccurrenceIsAtomicAndTemplateDeletionKeepsExpenses() = runTest {
        val id = recurring.upsert(
            RecurringExpense.create(Money(99900, CurrencyCode.RSD), 9, 1, Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 5), tags = listOf("подписка")),
        )
        assertEquals(1, recurring.getDue(LocalDate.of(2026, 10, 14)).size)

        val at = Instant.parse("2026-10-05T10:00:00Z")
        assertTrue(
            recurring.recordOccurrence(
                Expense.create(Money(99900, CurrencyCode.RSD), 9, 1, at, ZoneOffset.UTC, null, at, tags = listOf("подписка"), recurringId = id),
                id,
                from = LocalDate.of(2026, 10, 5),
                nextRunDate = LocalDate.of(2026, 11, 5),
                active = true,
            ),
        )
        assertEquals(LocalDate.of(2026, 11, 5), recurring.get(id)!!.nextRunDate)
        assertEquals(0, recurring.getDue(LocalDate.of(2026, 10, 14)).size)
        val created = db.expenseDao().getWithoutRates().single()
        assertEquals(id, created.expense.recurringId)
        assertEquals(listOf("подписка"), created.tags.map { it.name })

        recurring.delete(id)
        assertNull(recurring.get(id))
        // Расход остался, ссылка на шаблон обнулилась (FK ON DELETE SET NULL)
        assertNull(expenses.get(created.expense.id)!!.recurringId)
    }

    @Test
    fun recordOccurrenceForChangedOrDeletedTemplateDoesNothing() = runTest {
        val id = recurring.upsert(
            RecurringExpense.create(Money(99900, CurrencyCode.RSD), 9, 1, Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 5)),
        )
        val at = Instant.parse("2026-10-05T10:00:00Z")
        fun occurrence() = Expense.create(Money(99900, CurrencyCode.RSD), 9, 1, at, ZoneOffset.UTC, null, at, recurringId = id)
        val oct5 = LocalDate.of(2026, 10, 5)
        val nov5 = LocalDate.of(2026, 11, 5)

        // Шаблон уже сдвинут другим запуском — второй раз ту же дату не создаём.
        assertTrue(recurring.recordOccurrence(occurrence(), id, oct5, nov5, active = true))
        assertFalse(recurring.recordOccurrence(occurrence(), id, oct5, nov5, active = true))

        recurring.delete(id) // удалили с экрана, пока запуск держал шаблон в памяти
        assertFalse(recurring.recordOccurrence(occurrence(), id, nov5, nov5.plusMonths(1), active = true))
        assertEquals(1, db.expenseDao().getWithoutRates().size)
    }
}

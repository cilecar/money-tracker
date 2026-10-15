package app.moneytracker.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.data.repository.CaptureRepositoryImpl
import app.moneytracker.data.repository.ExpenseRepositoryImpl
import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.capture.ParsedPurchase
import app.moneytracker.domain.capture.RawNotification
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class CaptureStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-15T17:00:00Z"), ZoneId.of("Europe/Belgrade"))
    private lateinit var db: AppDatabase
    private lateinit var repo: CaptureRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(DatabaseSeeder(context)).allowMainThreadQueries().build()
        repo = CaptureRepositoryImpl(db.captureDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private fun purchase(at: Instant, key: String = "k") = CapturedPurchase.from(
        ParsedPurchase("wallet", Money(1600_00, CurrencyCode.RSD), "BAKERY", "1234", at, key),
        clock.instant(),
    )

    @Test
    fun pendingWindowAndConfirmation() = runTest {
        val t = Instant.parse("2026-10-15T16:59:31Z")
        val id = repo.insert(purchase(t))
        repo.insert(purchase(t.plusSeconds(3600), "k2"))
        assertEquals(2, repo.observePending().first().size)
        // Окно поиска дублей: ±5 минут.
        assertEquals(listOf(id), repo.getBetween(t.minusSeconds(300), t.plusSeconds(300)).map { it.id })

        val expenseId = ExpenseRepositoryImpl(db, db.expenseDao()).insert(
            Expense.create(Money(1600_00, CurrencyCode.RSD), 1, 1, t, clock.zone, null, clock.instant(), "BAKERY"),
        )
        repo.setStatus(id, CaptureStatus.CONFIRMED, expenseId)
        assertEquals(1, repo.observePending().first().size)
        assertEquals(expenseId, repo.get(id)!!.expenseId)
        // Удалили расход — запись о покупке остаётся, ссылка обнуляется.
        db.expenseDao().delete(expenseId)
        assertNull(repo.get(id)!!.expenseId)
        assertEquals(CaptureStatus.CONFIRMED, repo.get(id)!!.status)
    }

    @Test
    fun merchantRuleIsOverwrittenAndDiesWithCategory() = runTest {
        repo.saveRule("nicefoods", 1)
        repo.saveRule("nicefoods", 2)
        assertEquals(2L, repo.ruleFor("nicefoods"))
        db.categoryDao().deleteMovingReferences(2, null)
        assertNull(repo.ruleFor("nicefoods"))
    }

    @Test
    fun debugStoreKeepsLast50AndSurvivesRestart() = runTest {
        File(context.noBackupFilesDir, "capture-debug.json").delete()
        val store = NotificationDebugStore(context)
        repeat(55) { i ->
            store.add(RawNotification("com.google.android.apps.walletnfcrel", "k$i", "M$i", "RSD$i with card", null, null, Instant.ofEpochSecond(i.toLong())))
        }
        assertEquals(50, store.items.value!!.size)
        assertEquals("M54", store.items.value!!.first().title)

        val reopened = NotificationDebugStore(context)
        reopened.load()
        assertEquals(50, reopened.items.value!!.size)
        assertTrue(reopened.items.value!!.first().describe().contains("RSD54 with card"))
        reopened.clear()
        assertEquals(0, NotificationDebugStore(context).also { it.load() }.items.value!!.size)
    }
}

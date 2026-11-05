package app.moneytracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.DatabaseSeeder
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.receipt.FetchedItem
import app.moneytracker.domain.receipt.FetchedReceipt
import app.moneytracker.domain.receipt.FiscalLink
import app.moneytracker.domain.receipt.ItemNameParser
import app.moneytracker.domain.receipt.ItemToSave
import app.moneytracker.domain.receipt.ItemUnit
import app.moneytracker.domain.receipt.ReceiptStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Чеки, сети, артикулы и продукты на настоящей базе: UNIQUE, каскады, SET NULL, очистка сирот. */
@RunWith(RobolectricTestRunner::class)
class ReceiptRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var receipts: ReceiptRepositoryImpl
    private lateinit var expenses: ExpenseRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl

    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-11-05T16:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val issuedAt = Instant.parse("2026-11-05T14:28:04.875Z")
    private var food = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(DatabaseSeeder(context))
            .allowMainThreadQueries()
            .build()
        receipts = ReceiptRepositoryImpl(db, db.receiptDao(), clock)
        expenses = ExpenseRepositoryImpl(db, db.expenseDao())
        categories = CategoryRepositoryImpl(db.categoryDao())
        food = categories.getAll().first().id
    }

    @After
    fun tearDown() = db.close()

    private fun link(number: String = "WYBQ8YRF-WYBQ8YRF-360050", at: Instant = issuedAt) =
        FiscalLink("https://suf.purs.gov.rs/v/?vl=$number", number, 119796, at)

    private fun item(raw: String, qty: Long, price: Long, total: Long = price * qty / 1000) = FetchedItem(raw, qty, price, total, 20)

    private fun fetched(
        items: List<FetchedItem> = listOf(
            item("Pileći file/KG/0238588", 1000, 77999),
            item("Bolonjeze sos/KOM/0200689", 1000, 19999),
            item("Bolonjeze sos/KOM/0200689", 1000, 19999),
            item("Papirna kesa Mala/KOM/9004925", 1000, 1799),
        ),
        taxId: String = "106884584",
        name: String = "LIDL SRBIJA KD",
        at: Instant = issuedAt,
    ) = FetchedReceipt(taxId, name, "1056845", "1056845-Prodavnica br. 0123", "ОБРЕНОВАЧКИ ДРУМ 1", "БЕОГРАД (ЧУКАРИЦА)", 119796, at, "journal", items)

    private fun toSave(f: FetchedReceipt) = f.items.map { ItemToSave(it, if (it.totalMinor < 0) null else ItemNameParser.parse(it.rawName)) }

    private suspend fun loaded(number: String = "WYBQ8YRF-WYBQ8YRF-360050", f: FetchedReceipt = fetched()): Long {
        val id = receipts.insertPending(link(number, f.issuedAt), now)!!
        receipts.saveLoaded(id, f, toSave(f))
        return id
    }

    private suspend fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT count(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun productOf(raw: String): Long =
        receipts.observeAllArticles().first().single { it.article.name == ItemNameParser.parse(raw).name && it.chain.taxId == "106884584" }.article.productId

    private suspend fun expense(): Long =
        expenses.insert(Expense.create(Money(119796, CurrencyCode.RSD), food, 1, issuedAt, zone, null, now))

    @Test
    fun insertPendingIsIdempotent() = runTest {
        assertNotNull(receipts.insertPending(link(), now))
        assertNull(receipts.insertPending(link(), now))
        assertEquals(1, count("receipt"))
    }

    @Test
    fun saveLoadedCreatesChainStoreArticlesProducts() = runTest {
        val id = loaded()
        assertEquals(1, count("chain"))
        assertEquals(1, count("store"))
        assertEquals(3, count("article"))
        assertEquals(3, count("product"))
        assertEquals(4, count("receipt_item"))
        val r = receipts.get(id)!!
        assertEquals(ReceiptStatus.LOADED, r.status)
        assertNotNull(r.storeId)
        val details = receipts.observeDetails(id).first()!!
        assertEquals("LIDL SRBIJA KD", details.chain!!.officialName)
        assertEquals(listOf(0, 1, 2, 3), details.items.map { it.position })
        assertEquals(ItemUnit.KG, details.articles.getValue(details.items[0].articleId!!).unit)
    }

    @Test
    fun saveLoadedTwiceReplacesItems() = runTest {
        val id = loaded()
        receipts.saveLoaded(id, fetched(), toSave(fetched()))
        assertEquals(4, count("receipt_item"))
        assertEquals(3, count("article"))
        assertEquals(3, count("product"))
    }

    @Test
    fun secondReceiptReusesArticles() = runTest {
        loaded()
        loaded("OTHER-1", fetched(at = issuedAt.plusSeconds(86400)))
        assertEquals(3, count("article"))
        assertEquals(3, count("product"))
        assertEquals(1, count("store"))
        val points = receipts.observePricePoints(productOf("Bolonjeze sos/KOM/0200689")).first()
        assertEquals(4, points.size)
        assertTrue(points.all { it.unitPriceMinor == 19999L })
        assertEquals(LocalDate.of(2026, 11, 5), points.minOf { it.date })
    }

    @Test
    fun discountItemsHaveNoArticleAndNoPricePoint() = runTest {
        val id = loaded(f = fetched(items = listOf(item("Bolonjeze sos/KOM/0200689", 1000, 19999), item("Popust", 1000, -2000))))
        val details = receipts.observeDetails(id).first()!!
        assertNull(details.items[1].articleId)
        assertTrue(details.items[1].isDiscount)
        assertEquals(1, receipts.observePricePoints().first().size)
    }

    @Test
    fun pendingReceiptHasNoPricePoints() = runTest {
        receipts.insertPending(link(), now)
        assertTrue(receipts.observePricePoints().first().isEmpty())
        assertEquals(1, receipts.getPendingIds().size)
    }

    @Test
    fun expenseDeleteSetsNullAndUndoReattaches() = runTest {
        val r = loaded()
        val e = expense()
        assertTrue(receipts.attach(r, e))
        val deleted = expenses.deleteForUndo(e)!!
        assertNull(receipts.get(r)!!.expenseId)
        assertEquals(r, deleted.receiptId)
        expenses.restore(deleted)
        assertEquals(e, receipts.get(r)!!.expenseId)
    }

    @Test
    fun undoDoesNotStealReceiptAttachedElsewhere() = runTest {
        val r = loaded()
        val e = expense()
        receipts.attach(r, e)
        val deleted = expenses.deleteForUndo(e)!!
        val other = expense()
        receipts.attach(r, other)
        expenses.restore(deleted)
        assertEquals(other, receipts.get(r)!!.expenseId)
    }

    @Test
    fun attachRejectsSecondReceiptForExpense() = runTest {
        val a = loaded()
        val b = loaded("OTHER-1")
        val e = expense()
        assertTrue(receipts.attach(a, e))
        assertFalse(receipts.attach(b, e))
        assertEquals(e, receipts.getByExpense(e)!!.id.let { receipts.get(it)!!.expenseId })
        assertEquals(setOf(e), receipts.expensesWithReceipt(listOf(e, 999)))
    }

    @Test
    fun deleteRemovesOrphans() = runTest {
        val id = loaded()
        receipts.delete(id)
        assertEquals(0, count("receipt_item"))
        assertEquals(0, count("article"))
        assertEquals(0, count("product"))
        assertEquals(1, count("chain"))
        assertEquals(1, count("store"))
    }

    @Test
    fun deleteKeepsArticlesUsedByOtherReceipts() = runTest {
        val a = loaded()
        loaded("OTHER-1")
        receipts.delete(a)
        assertEquals(3, count("article"))
        assertEquals(4, count("receipt_item"))
    }

    @Test
    fun mergeAndDetach() = runTest {
        loaded()
        loaded("MAXI-1", fetched(items = listOf(item("SOS BOLONJEZE", 1000, 21999)), taxId = "100000000", name = "DELHAIZE"))
        val lidlSauce = productOf("Bolonjeze sos/KOM/0200689")
        val all = receipts.observeAllArticles().first()
        val maxi = all.single { it.chain.taxId == "100000000" }
        val chicken = productOf("Pileći file/KG/0238588")

        assertFalse(receipts.mergeProducts(chicken, maxi.article.productId))
        assertFalse(receipts.mergeProducts(lidlSauce, lidlSauce))
        assertTrue(receipts.mergeProducts(lidlSauce, maxi.article.productId))
        assertNull(receipts.observeProduct(maxi.article.productId).first())
        assertEquals(2, receipts.observeArticles(lidlSauce).first().size)
        assertEquals(setOf("106884584", "100000000"), receipts.observePricePoints(lidlSauce).first().map { p -> all.first { it.chain.id == p.chainId }.chain.taxId }.toSet())

        val newProduct = receipts.detachArticle(maxi.article.id)
        assertNotNull(newProduct)
        assertEquals("SOS BOLONJEZE", receipts.observeProduct(newProduct!!).first()!!.name)
        assertNull(receipts.detachArticle(maxi.article.id))
    }

    @Test
    fun chainSettingsAndCategoryDeleteSetsNull() = runTest {
        val id = loaded()
        val chain = receipts.chainOfReceipt(id)!!
        val temp = categories.upsert(Category(name = "Временная", iconKey = "food", color = 0))
        receipts.setChainShortName(chain.id, "Lidl")
        receipts.setChainDefaultCategory(chain.id, temp)
        assertEquals("Lidl", receipts.chainOfReceipt(id)!!.displayName)
        assertEquals(temp, receipts.chainOfReceipt(id)!!.defaultCategoryId)
        categories.delete(temp, null)
        assertNull(receipts.chainOfReceipt(id)!!.defaultCategoryId)
        // Повторная загрузка не затирает настройки пользователя.
        receipts.saveLoaded(id, fetched(), toSave(fetched()))
        assertEquals("Lidl", receipts.chainOfReceipt(id)!!.shortName)
        val chains = receipts.observeChains().first()
        assertEquals(1, chains.single().receiptCount)
    }

    @Test
    fun rowsAndStatus() = runTest {
        val a = loaded()
        val b = receipts.insertPending(link("OTHER-1"), now)!!
        receipts.markFailed(b, "Чек не прошёл проверку налоговой")
        val rows = receipts.observeRows().first()
        assertEquals(2, rows.size)
        assertEquals(ReceiptStatus.FAILED, rows.single { it.receipt.id == b }.receipt.status)
        assertEquals("LIDL SRBIJA KD", rows.single { it.receipt.id == a }.chain!!.officialName)
        receipts.markPending(b)
        assertEquals(listOf(b), receipts.getPendingIds())
        assertNull(receipts.get(b)!!.error)
    }
}

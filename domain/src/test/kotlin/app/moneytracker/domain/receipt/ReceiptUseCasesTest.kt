package app.moneytracker.domain.receipt

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.fake.FakeCaptureRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeReceiptRepository
import app.moneytracker.domain.fake.FakeReceiptSyncScheduler
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.receipt.FiscalLinkParserTest.Companion.LIDL_URL
import app.moneytracker.domain.usecase.AttachReceiptUseCase
import app.moneytracker.domain.usecase.FindReceiptCandidatesUseCase
import app.moneytracker.domain.usecase.IntakeReceiptUseCase
import app.moneytracker.domain.usecase.IntakeResult
import app.moneytracker.domain.usecase.LoadOutcome
import app.moneytracker.domain.usecase.LoadReceiptUseCase
import app.moneytracker.domain.usecase.PrefillExpenseFromReceiptUseCase
import app.moneytracker.domain.usecase.ReceiptCandidate
import app.moneytracker.domain.usecase.ReceiptPrefill
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ReceiptUseCasesTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-11-05T16:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val issuedAt = Instant.parse("2026-11-05T14:28:04.875Z")

    private val receipts = FakeReceiptRepository()
    private val expenses = FakeExpenseRepository()
    private val captures = FakeCaptureRepository()
    private val sync = FakeReceiptSyncScheduler()

    private class FakeSource(var result: suspend () -> FetchResult) : ReceiptSource {
        var calls = 0
        override suspend fun fetch(url: String): FetchResult { calls++; return result() }
    }

    private val fetched = FetchedReceipt(
        taxId = "106884584", officialName = "LIDL SRBIJA KD", locationCode = "1056845",
        locationName = "1056845-Prodavnica br. 0123", address = "ОБРЕНОВАЧКИ ДРУМ 1", city = "БЕОГРАД (ЧУКАРИЦА)",
        totalMinor = 119796, issuedAt = issuedAt, journal = "journal",
        items = listOf(
            FetchedItem("Bolonjeze sos/KOM/0200689", 1000, 19999, 19999, 20),
            FetchedItem("Popust", 1000, -2000, -2000, 20),
        ),
    )
    private val source = FakeSource({ FetchResult.Ok(fetched) })
    private val load = LoadReceiptUseCase(receipts, source)
    private val intake = IntakeReceiptUseCase(receipts, load, sync, clock)

    private fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

    private suspend fun expense(amount: Money, at: String, categoryId: Long = 1): Long = expenses.insert(
        Expense.create(amount, categoryId, accountId = 1, occurredAt = Instant.parse(at), zone = zone, rates = null, now = now),
    )

    private suspend fun capture(amount: Money, at: String, status: CaptureStatus): CapturedPurchase {
        val id = captures.insert(
            CapturedPurchase(
                source = "wallet", amount = amount, merchant = "LIDL", merchantKey = "lidl", cardSuffix = null,
                occurredAt = Instant.parse(at), notificationKey = at, status = status, createdAt = now,
            ),
        )
        return captures.get(id)!!
    }

    // --- Приём чека ---

    @Test
    fun `added and loaded`() = runTest {
        val r = intake(LIDL_URL)
        assertTrue(r is IntakeResult.Added && r.loaded)
        val id = (r as IntakeResult.Added).receiptId
        assertEquals(ReceiptStatus.LOADED, receipts.get(id)!!.status)
        assertEquals(1, receipts.saved.size)
        assertEquals(0, sync.calls)
    }

    @Test
    fun `offline leaves pending and schedules sync`() = runTest {
        source.result = { FetchResult.Unavailable }
        val r = intake(LIDL_URL) as IntakeResult.Added
        assertEquals(false, r.loaded)
        assertEquals(ReceiptStatus.PENDING, receipts.get(r.receiptId)!!.status)
        assertEquals(1, sync.calls)
    }

    @Test
    fun `slow source times out`() = runTest {
        source.result = { delay(10_000); FetchResult.Ok(fetched) }
        val r = intake(LIDL_URL, Duration.ofMillis(100)) as IntakeResult.Added
        assertEquals(false, r.loaded)
        assertEquals(ReceiptStatus.PENDING, receipts.get(r.receiptId)!!.status)
        assertEquals(1, sync.calls)
    }

    @Test
    fun `duplicate`() = runTest {
        val first = intake(LIDL_URL) as IntakeResult.Added
        receipts.attach(first.receiptId, 42)
        assertEquals(IntakeResult.Duplicate(first.receiptId, 42), intake(LIDL_URL))
        assertEquals(1, receipts.receipts.value.size)
        assertEquals(1, source.calls)
    }

    @Test
    fun `not fiscal and unsupported are passed through without insert`() = runTest {
        assertEquals(IntakeResult.NotFiscal, intake("https://example.com"))
        assertEquals(IntakeResult.NotFiscal, intake("https://suf.purs.gov.rs/v/?vl=abc"))
        assertTrue(receipts.receipts.value.isEmpty())
    }

    // --- Загрузка ---

    @Test
    fun `discounts get no article`() = runTest {
        val id = receipts.insertPending(link(), now)!!
        assertEquals(LoadOutcome.LOADED, load(id))
        val items = receipts.saved.single().second
        assertEquals(ItemNameParser.parse("Bolonjeze sos/KOM/0200689"), items[0].parsed)
        assertNull(items[1].parsed)
    }

    @Test
    fun `invalid marks failed`() = runTest {
        source.result = { FetchResult.Invalid("x") }
        val id = receipts.insertPending(link(), now)!!
        assertEquals(LoadOutcome.FAILED, load(id))
        assertEquals(ReceiptStatus.FAILED, receipts.get(id)!!.status)
        assertEquals("x", receipts.get(id)!!.error)
    }

    @Test
    fun `unavailable keeps pending`() = runTest {
        source.result = { FetchResult.Unavailable }
        val id = receipts.insertPending(link(), now)!!
        assertEquals(LoadOutcome.UNAVAILABLE, load(id))
        assertEquals(ReceiptStatus.PENDING, receipts.get(id)!!.status)
    }

    private fun link(at: Instant = issuedAt) = FiscalLink(LIDL_URL, "WYBQ8YRF-WYBQ8YRF-360050", 119796, at)

    // --- Кандидаты ---

    @Test
    fun `candidates match amount and day, sorted by time`() = runTest {
        val a = expense(rsd(119796), "2026-11-05T14:29:00Z")
        expense(rsd(119796), "2026-11-04T14:29:00Z")
        val c = expense(rsd(120000), "2026-11-05T10:00:00Z")
        val d = expense(Money(119796, CurrencyCode.EUR), "2026-11-05T09:00:00Z")
        val e = expense(rsd(119796), "2026-11-05T14:28:30Z")
        val p = capture(rsd(119796), "2026-11-05T14:28:10Z", CaptureStatus.PENDING)
        capture(rsd(119796), "2026-11-05T12:00:00Z", CaptureStatus.CONFIRMED)

        val other = receipts.insertPending(link().copy(invoiceNumber = "OTHER"), now)!!
        receipts.attach(other, e)
        val id = receipts.insertPending(link(), now)!!

        val result = FindReceiptCandidatesUseCase(expenses, captures, receipts, clock)(id)
        assertEquals(listOf(ReceiptCandidate.OfCapture(p), ReceiptCandidate.OfExpense(expenses.get(a)!!)), result.matching)
        assertEquals(listOf(a, c, d), result.sameDay.map { it.id })
    }

    @Test
    fun `day boundary uses user zone`() = runTest {
        // 00:30 6 ноября по Белграду.
        val late = Instant.parse("2026-11-05T23:30:00Z")
        val sixth = expense(rsd(119796), "2026-11-05T23:40:00Z")
        expense(rsd(119796), "2026-11-05T20:00:00Z")
        val id = receipts.insertPending(link(late), now)!!

        val result = FindReceiptCandidatesUseCase(expenses, captures, receipts, clock)(id)
        assertEquals(listOf(sixth), result.matching.map { (it as ReceiptCandidate.OfExpense).expense.id })
    }

    // --- Привязка ---

    @Test
    fun `attach remembers category on chain`() = runTest {
        val r = (intake(LIDL_URL) as IntakeResult.Added).receiptId
        val e = expense(rsd(119796), "2026-11-05T14:29:00Z")
        assertTrue(AttachReceiptUseCase(receipts)(r, e, rememberCategoryId = 7))
        assertEquals(e, receipts.get(r)!!.expenseId)
        assertEquals(7L, receipts.chainOfReceipt(r)!!.defaultCategoryId)
    }

    @Test
    fun `attach without remember keeps chain category`() = runTest {
        val r = (intake(LIDL_URL) as IntakeResult.Added).receiptId
        assertTrue(AttachReceiptUseCase(receipts)(r, 5))
        assertNull(receipts.chainOfReceipt(r)!!.defaultCategoryId)
    }

    @Test
    fun `attach of pending receipt does not fail`() = runTest {
        val r = receipts.insertPending(link(), now)!!
        assertTrue(AttachReceiptUseCase(receipts)(r, 5, rememberCategoryId = 7))
        assertNull(receipts.chainOfReceipt(r))
    }

    // --- Заполнение нового расхода ---

    @Test
    fun `prefill from loaded receipt uses chain name and category`() = runTest {
        val r = (intake(LIDL_URL) as IntakeResult.Added).receiptId
        val chain = receipts.chainOfReceipt(r)!!
        receipts.setChainShortName(chain.id, "Lidl")
        receipts.setChainDefaultCategory(chain.id, 3)
        assertEquals(ReceiptPrefill(rsd(119796), issuedAt, "Lidl", 3), PrefillExpenseFromReceiptUseCase(receipts)(r))
    }

    @Test
    fun `prefill from pending receipt has no comment and category`() = runTest {
        val r = receipts.insertPending(link(), now)!!
        assertEquals(ReceiptPrefill(rsd(119796), issuedAt, null, null), PrefillExpenseFromReceiptUseCase(receipts)(r))
        assertNull(PrefillExpenseFromReceiptUseCase(receipts)(999))
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.GoogleWalletParser
import app.moneytracker.domain.capture.RawNotification
import app.moneytracker.domain.fake.FakeAccountRepository
import app.moneytracker.domain.fake.FakeCaptureRepository
import app.moneytracker.domain.fake.FakeCategoryRepository
import app.moneytracker.domain.fake.FakeExchangeRateRepository
import app.moneytracker.domain.fake.FakeExpenseRepository
import app.moneytracker.domain.fake.FakeSettingsRepository
import app.moneytracker.domain.fake.checkBudgets
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CaptureUseCasesTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = Instant.parse("2026-10-15T17:00:00Z")
    private val clock = Clock.fixed(now, zone)
    private val captures = FakeCaptureRepository()
    private val categories = FakeCategoryRepository(
        listOf(Category(id = 1, name = "Еда", iconKey = "", color = 0), Category(id = 2, name = "Продукты", iconKey = "", color = 0)),
    )
    private val accounts = FakeAccountRepository(
        listOf(
            Account(id = 1, name = "Наличные", type = AccountType.CASH, currency = CurrencyCode.RSD, openingDate = LocalDate.of(2026, 1, 1)),
            Account(id = 2, name = "Bybit", type = AccountType.CARD, currency = CurrencyCode.EUR, openingDate = LocalDate.of(2026, 1, 1), cardSuffix = "1234"),
        ),
    )
    private val expenses = FakeExpenseRepository()
    private val rates = FakeExchangeRateRepository(clock)
    private val suggest = SuggestCaptureUseCase(captures, categories, accounts)
    private val capture = CapturePurchaseUseCase(setOf(GoogleWalletParser()), captures, rates, suggest, clock)
    private val confirm = ConfirmCaptureUseCase(
        captures, suggest, SaveExpenseUseCase(expenses, rates, FakeSettingsRepository(), checkBudgets(clock, expenses, categories), clock),
    )

    private fun wallet(text: String = "RSD1,600 with Bybit Mastercard ••1234", key: String = "k1", title: String = "BAKERY", at: Instant = now) =
        RawNotification(GoogleWalletParser.PACKAGE, key, title, text, null, null, at)

    @Test
    fun `purchase goes to pending, not to expenses`() = runTest {
        val result = capture(wallet()) as CaptureResult.Captured
        assertEquals(CaptureStatus.PENDING, result.purchase.status)
        assertEquals(Money(1600_00, CurrencyCode.RSD), result.purchase.amount)
        // Счёт найден по цифрам карты, правила для мерчанта ещё нет — только «Изменить».
        assertEquals(2L, result.suggestion.accountId)
        assertNull(result.suggestion.categoryId)
        assertFalse(result.suggestion.canAddDirectly)
        assertTrue(expenses.items.value.isEmpty())
    }

    @Test
    fun `other apps and non-purchases are ignored`() = runTest {
        assertEquals(CaptureResult.Ignored, capture(wallet().copy(packageName = "com.bybit.app")))
        assertEquals(CaptureResult.Ignored, capture(wallet(text = "View the details", title = "Purchase complete")))
        assertTrue(captures.items.value.isEmpty())
    }

    @Test
    fun `repeated push is a duplicate`() = runTest {
        capture(wallet())
        assertTrue(capture(wallet()) is CaptureResult.Duplicate)
        assertTrue(capture(wallet(key = "k2", at = now.plusSeconds(120))) is CaptureResult.Duplicate)
        assertEquals(1, captures.items.value.size)
    }

    @Test
    fun `confirm creates expense on the card and remembers category for the merchant`() = runTest {
        val first = (capture(wallet()) as CaptureResult.Captured).purchase
        val expenseId = confirm(first.id, categoryId = 2)!!
        val e = expenses.get(expenseId)!!
        assertEquals(Money(1600_00, CurrencyCode.RSD), e.amount)
        assertEquals(2L, e.accountId)
        assertEquals("BAKERY", e.comment)
        assertEquals(first.occurredAt, e.occurredAt)
        assertEquals(CaptureStatus.CONFIRMED, captures.get(first.id)!!.status)
        // Повторное нажатие не создаёт второй расход.
        assertEquals(expenseId, confirm(first.id, categoryId = 2))
        assertEquals(1, expenses.items.value.size)

        // Следующая покупка там же — категория уже известна, можно добавить в один тап.
        val next = capture(wallet(key = "k3", at = now.plusSeconds(3600))) as CaptureResult.Captured
        assertEquals(2L, next.suggestion.categoryId)
        assertTrue(next.suggestion.canAddDirectly)
        assertTrue(confirm(next.purchase.id) != null)
    }

    @Test
    fun `without card match confirm needs an explicit account`() = runTest {
        val p = (capture(wallet(text = "RSD350 with Visa ••1111")) as CaptureResult.Captured).purchase
        assertNull(confirm(p.id, categoryId = 1))
        assertTrue(confirm(p.id, categoryId = 1, accountId = 1) != null)
    }

    @Test
    fun `rule pointing to an archived category is not suggested`() = runTest {
        captures.rules["nicefoods"] = 2
        categories.setArchived(listOf(2), true)
        val r = capture(wallet()) as CaptureResult.Captured
        assertNull(r.suggestion.categoryId)
    }

    @Test
    fun `dismiss keeps it out of pending`() = runTest {
        val p = (capture(wallet()) as CaptureResult.Captured).purchase
        DismissCaptureUseCase(captures)(p.id)
        assertEquals(CaptureStatus.DISMISSED, captures.get(p.id)!!.status)
        assertTrue(expenses.items.value.isEmpty())
    }
}

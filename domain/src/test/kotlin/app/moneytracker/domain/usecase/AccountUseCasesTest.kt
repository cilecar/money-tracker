package app.moneytracker.domain.usecase

import java.time.LocalDate
import app.moneytracker.domain.fake.FakeAccountRepository
import app.moneytracker.domain.fake.FakeSettingsRepository
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.AccountBalance
import app.moneytracker.domain.model.AccountsTotal
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountUseCasesTest {

    private val repo = FakeAccountRepository(
        listOf(
            Account(id = 1, name = "Наличные", type = AccountType.CASH, currency = CurrencyCode.RSD, openingDate = OPENING),
            Account(id = 2, name = "Карта RSD", type = AccountType.CARD, currency = CurrencyCode.RSD, sortOrder = 1, openingDate = OPENING),
        ),
    )
    private val settings = FakeSettingsRepository()
    private val save = SaveAccountUseCase(repo)
    private val delete = DeleteAccountUseCase(repo, settings)
    private val archive = ArchiveAccountUseCase(repo, settings)

    private fun AccountResult.error() = (this as? AccountResult.Error)?.error

    @Test
    fun `save validates name`() = runTest {
        assertEquals(AccountError.EMPTY_NAME, save(Account(name = " ", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = OPENING)).error())
        assertEquals(AccountError.DUPLICATE_NAME, save(Account(name = "карта rsd", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = OPENING)).error())
        val id = (save(Account(name = "Карта ₽", type = AccountType.CARD, currency = CurrencyCode.RUB, openingDate = OPENING)) as AccountResult.Success).id
        assertEquals(2, repo.get(id)!!.sortOrder)
    }

    @Test
    fun `deleting default method clears the setting`() = runTest {
        settings.setDefaultAccount(2)
        repo.expenseCounts[2] = 3
        assertEquals(AccountError.NEEDS_TARGET, delete(2).error())
        assertTrue(delete(2, moveTo = 1) is AccountResult.Success)
        assertEquals(3, repo.expenseCounts[1])
        assertNull(settings.settings.value.defaultAccountId)
    }

    @Test
    fun `archiving default method clears the setting`() = runTest {
        settings.setDefaultAccount(1)
        archive(1, true)
        assertNull(settings.settings.value.defaultAccountId)
    }

    @Test
    fun `last active method is protected`() = runTest {
        archive(1, true)
        assertEquals(AccountError.LAST_ACTIVE, archive(2, true).error())
        assertEquals(AccountError.LAST_ACTIVE, delete(2).error())
    }

    @Test
    fun `restoring from archive keeps names unique`() = runTest {
        archive(2, true)
        save(Account(name = "карта rsd", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = OPENING))
        assertEquals(AccountError.DUPLICATE_NAME, archive(2, false).error())
        assertTrue(repo.getAll().first { it.id == 2L }.archived)
    }

    @Test
    fun `currency cannot change once the account has operations`() = runTest {
        val card = repo.get(2)!!
        assertTrue(save(card.copy(currency = CurrencyCode.EUR)) is AccountResult.Success)
        repo.expenseCounts[2] = 1
        assertEquals(AccountError.CURRENCY_LOCKED, save(card.copy(currency = CurrencyCode.RUB)).error())
        // Остальное менять можно.
        assertTrue(save(repo.get(2)!!.copy(name = "Карта €")) is AccountResult.Success)
    }

    @Test
    fun `reconcile records the signed difference`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-14T22:30:00Z"), ZoneId.of("Europe/Belgrade"))
        val reconcile = ReconcileAccountUseCase(repo, clock)
        repo.spent[1] = 300_00

        val up = reconcile(1, Money(-200_00, CurrencyCode.RSD), "  ")!!
        assertEquals(Money(100_00, CurrencyCode.RSD), up.amount)
        // Дата — по часовому поясу пользователя: в Белграде уже 15-е.
        assertEquals(LocalDate.of(2026, 10, 15), up.localDate)
        assertNull(up.comment)
        assertNull(reconcile(1, Money(-200_00, CurrencyCode.RSD)))
    }

    @Test
    fun `accounts total converts by current rates and flags missing ones`() {
        val rates = RateSnapshot(BigDecimal("117"), BigDecimal("95"), OPENING)
        val rsd = AccountBalance(repo.items.value[0], Money(1170_00, CurrencyCode.RSD))
        val eur = AccountBalance(Account(id = 3, name = "€", type = AccountType.CARD, currency = CurrencyCode.EUR, openingDate = OPENING), Money(-5_00, CurrencyCode.EUR), unconverted = 2)
        val archived = AccountBalance(Account(id = 4, name = "old", type = AccountType.CARD, currency = CurrencyCode.EUR, openingDate = OPENING, archived = true), Money(1_000_00, CurrencyCode.EUR))

        val total = AccountsTotal.of(listOf(rsd, eur, archived), CurrencyCode.EUR, rates)
        assertEquals(Money(5_00, CurrencyCode.EUR), total.total)
        assertFalse(total.missingRates)
        assertFalse(total.exact)

        val offline = AccountsTotal.of(listOf(rsd, eur), CurrencyCode.RSD, null)
        assertEquals(Money(1170_00, CurrencyCode.RSD), offline.total)
        assertTrue(offline.missingRates)
    }
}

private val OPENING: LocalDate = LocalDate.of(2020, 1, 1)

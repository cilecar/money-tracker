package app.moneytracker.ui.debts

import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtDirection
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Что подставляет диалог «Погашено»: счёт расхода и ту же сумму, в другой валюте — по курсу. */
class SettleDefaultsTest {
    private val opening = LocalDate.of(2026, 1, 1)
    private val card = Account(id = 1, name = "Карта", type = AccountType.CARD, currency = CurrencyCode.RSD, openingDate = opening)
    private val euro = Account(id = 2, name = "Евро", type = AccountType.CARD, currency = CurrencyCode.EUR, openingDate = opening)
    private val rates = RateSnapshot(BigDecimal("117"), BigDecimal("95"), LocalDate.of(2026, 10, 1))

    private fun debt(direction: DebtDirection, expenseAccount: Long?) = Debt(
        DebtRef(direction, 1), 1, Money(117000, CurrencyCode.RSD), 1, LocalDate.of(2026, 10, 12), null, expenseAccount, null,
    )

    @Test
    fun `owed to me defaults to expense account, paid for me to default account`() {
        val state = PersonDebtsUiState(accounts = listOf(card, euro), defaultAccountId = 2, rates = rates)
        assertEquals(card, state.defaultAccountFor(debt(DebtDirection.OWED_TO_ME, expenseAccount = 1)))
        assertEquals(euro, state.defaultAccountFor(debt(DebtDirection.I_OWE, expenseAccount = null)))
    }

    @Test
    fun `amount is the same in debt currency and converted otherwise`() {
        val state = PersonDebtsUiState(accounts = listOf(card, euro), rates = rates)
        assertEquals("1170", state.defaultAmountText(debt(DebtDirection.OWED_TO_ME, 1), card))
        assertEquals("10", state.defaultAmountText(debt(DebtDirection.OWED_TO_ME, 1), euro))
        assertEquals("", state.copy(rates = null).defaultAmountText(debt(DebtDirection.OWED_TO_ME, 1), euro))
    }
}

package app.moneytracker.ui.debts

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DebtSummary
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.PersonBalance
import org.junit.Assert.assertEquals
import org.junit.Test

class DebtsUiStateTest {
    private val petya = Person(id = 1, name = "Петя")
    private val vasya = Person(id = 2, name = "Вася")
    private val masha = Person(id = 3, name = "Маша", archived = true)
    private val olya = Person(id = 4, name = "Оля", archived = true)

    @Test
    fun `people split into open debts, others and archive`() {
        val rsd = Money(100, CurrencyCode.RSD)
        // У Оли (в архиве) открытый долг — она в основном списке, а не в архиве.
        val summary = DebtSummary(rsd, rsd, listOf(PersonBalance(petya, rsd, 1, false), PersonBalance(olya, rsd, 1, false)), false)
        val state = debtsUiState(summary, listOf(petya, vasya, masha, olya))
        assertEquals(listOf(vasya), state.others)
        // Архивного без долгов иначе не открыть и не вернуть из архива.
        assertEquals(listOf(masha), state.archived)
    }
}

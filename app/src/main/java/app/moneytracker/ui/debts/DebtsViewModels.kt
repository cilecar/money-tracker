package app.moneytracker.ui.debts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.Debt
import app.moneytracker.domain.model.DebtRef
import app.moneytracker.domain.model.DebtSummary
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.PersonRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.ObserveDebtsUseCase
import app.moneytracker.domain.usecase.SavePersonUseCase
import app.moneytracker.domain.usecase.SettleDebtUseCase
import app.moneytracker.domain.usecase.UnsettleDebtUseCase
import app.moneytracker.ui.expenses.edit.parseShareAmount
import app.moneytracker.ui.expenses.edit.toDraftText
import app.moneytracker.ui.navigation.PersonDebtsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject

data class DebtsUiState(
    val summary: DebtSummary? = null,
    /** Люди без открытых долгов — чтобы открыть историю или переименовать. */
    val others: List<Person> = emptyList(),
    /** Архивные без открытых долгов — иначе их не открыть и не вернуть из архива. */
    val archived: List<Person> = emptyList(),
)

/** Архивный человек с открытым долгом остаётся в основном списке (он в [DebtSummary.people]). */
fun debtsUiState(summary: DebtSummary, people: List<Person>): DebtsUiState {
    val withDebts = summary.people.map { it.person.id }.toSet()
    val rest = people.filter { it.id !in withDebts }
    return DebtsUiState(summary, rest.filter { !it.archived }, rest.filter { it.archived })
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DebtsViewModel @Inject constructor(
    observeDebts: ObserveDebtsUseCase,
    persons: PersonRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<DebtsUiState> = combine(
        settings.settings.map { it.displayCurrency }.distinctUntilChanged().flatMapLatest { observeDebts(it) },
        persons.observeAll(includeArchived = true),
        ::debtsUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtsUiState())
}

/** Долг со справочниками — для строки на карточке человека. */
data class DebtRow(val debt: Debt, val category: Category?, val parentCategory: Category?)

data class PersonDebtsUiState(
    val person: Person? = null,
    val open: List<DebtRow> = emptyList(),
    val settled: List<DebtRow> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val defaultAccountId: Long? = null,
    val rates: RateSnapshot? = null,
) {
    /** Счёт погашения по умолчанию: счёт расхода («мне должны»), иначе счёт по умолчанию. */
    fun defaultAccountFor(debt: Debt): Account? =
        accounts.firstOrNull { it.id == debt.expenseAccountId } ?: accounts.firstOrNull { it.id == defaultAccountId } ?: accounts.firstOrNull()

    /** Сумма погашения по умолчанию: та же при совпадении валют, иначе — по текущему курсу. */
    fun defaultAmountText(debt: Debt, account: Account): String = when {
        debt.amount.currency == account.currency -> debt.amount.toDraftText()
        else -> rates?.convertOrNull(debt.amount, account.currency)?.toDraftText().orEmpty()
    }
}

@HiltViewModel
class PersonDebtsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val debts: DebtRepository,
    private val persons: PersonRepository,
    categories: CategoryRepository,
    accounts: AccountRepository,
    rates: ExchangeRateRepository,
    settings: SettingsRepository,
    private val settle: SettleDebtUseCase,
    private val unsettle: UnsettleDebtUseCase,
    private val savePerson: SavePersonUseCase,
    private val clock: Clock,
) : ViewModel() {
    private val personId = savedStateHandle.toRoute<PersonDebtsRoute>().personId

    private val references = combine(
        categories.observeAll(includeArchived = true),
        accounts.observeAll(includeArchived = false),
        rates.observeCurrent(),
        settings.settings,
    ) { cats, accs, current, s -> References(cats.associateBy { it.id }, accs, current?.snapshot, s.defaultAccountId) }

    val state: StateFlow<PersonDebtsUiState> = combine(
        persons.observeAll(includeArchived = true).map { list -> list.firstOrNull { it.id == personId } },
        debts.observeDebts(personId),
        references,
    ) { person, list, refs ->
        fun row(d: Debt) = refs.categories[d.categoryId].let { c -> DebtRow(d, c, c?.parentId?.let(refs.categories::get)) }
        PersonDebtsUiState(
            person = person,
            open = list.filter { !it.isSettled }.sortedByDescending { it.localDate }.map(::row),
            settled = list.filter { it.isSettled }.sortedByDescending { it.settlement!!.occurredAt }.map(::row),
            accounts = refs.accounts,
            defaultAccountId = refs.defaultAccountId,
            rates = refs.rates,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonDebtsUiState())

    /** @return false — сумма не разобрана (диалог остаётся открытым). */
    fun settle(ref: DebtRef, account: Account, amountText: String, dateTime: LocalDateTime): Boolean {
        val amount = parseShareAmount(amountText, account.currency)?.takeIf { it.minor > 0 } ?: return false
        viewModelScope.launch { settle(ref, account.id, amount, dateTime.atZone(clock.zone).toInstant()) }
        return true
    }

    fun unsettle(ref: DebtRef) {
        viewModelScope.launch { unsettle.invoke(ref) }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { savePerson(personId, name) }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { persons.setArchived(personId, archived) }
    }

    fun now(): LocalDateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)

    private data class References(
        val categories: Map<Long, Category>,
        val accounts: List<Account>,
        val rates: RateSnapshot?,
        val defaultAccountId: Long?,
    )
}

/** Знак суммы для строки долга: «мне должны» — плюс. */
fun Debt.signed(): Money = if (ref.direction == app.moneytracker.domain.model.DebtDirection.OWED_TO_ME) amount else -amount


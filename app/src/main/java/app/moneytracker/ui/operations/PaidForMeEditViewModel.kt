package app.moneytracker.ui.operations

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.PaidForMe
import app.moneytracker.domain.model.Person
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.PersonRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.PaidForMeInput
import app.moneytracker.domain.usecase.SavePaidForMeUseCase
import app.moneytracker.domain.usecase.SavePersonUseCase
import app.moneytracker.ui.expenses.edit.AmountInput
import app.moneytracker.ui.expenses.edit.KeypadKey
import app.moneytracker.ui.navigation.PaidForMeEditRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

data class PaidForMeForm(
    val loaded: Boolean = false,
    val amount: AmountInput = AmountInput(),
    val currency: CurrencyCode = CurrencyCode.RSD,
    val categoryId: Long? = null,
    val personId: Long? = null,
    val dateTime: LocalDateTime,
    val comment: String = "",
    val original: PaidForMe? = null,
    val saving: Boolean = false,
)

data class PaidForMeEditUiState(
    val form: PaidForMeForm,
    val conversions: List<Money>,
    val ratesDate: LocalDate?,
    val categories: List<Category>,
    val persons: List<Person>,
) {
    val isNew: Boolean get() = form.original == null
    val selectedCategory: Category? get() = categories.firstOrNull { it.id == form.categoryId }
    val canSave: Boolean get() = form.loaded && !form.saving && form.amount.toMoney(form.currency).minor > 0 &&
        form.categoryId != null && form.personId != null
}

/** «За меня заплатили»: как расход, только вместо счёта — кто заплатил. */
@HiltViewModel
class PaidForMeEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val debts: DebtRepository,
    categoryRepository: CategoryRepository,
    personRepository: PersonRepository,
    ratesRepository: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val save: SavePaidForMeUseCase,
    private val savePerson: SavePersonUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val id = savedStateHandle.toRoute<PaidForMeEditRoute>().id
    private val form = MutableStateFlow(PaidForMeForm(dateTime = LocalDateTime.now(clock).withSecond(0).withNano(0)))

    private val _close = Channel<Unit>(Channel.BUFFERED)
    val close = _close.receiveAsFlow()

    val state: StateFlow<PaidForMeEditUiState?> = combine(
        form,
        categoryRepository.observeAll(includeArchived = true),
        personRepository.observeAll(includeArchived = true),
        ratesRepository.observeCurrent(),
    ) { f, cats, people, current -> buildState(f, cats, people, current) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (id != 0L) {
            val p = debts.getPaidForMe(id) ?: return _close.send(Unit)
            form.value = PaidForMeForm(
                loaded = true,
                amount = AmountInput.from(p.amount),
                currency = p.amount.currency,
                categoryId = p.categoryId,
                personId = p.personId,
                dateTime = LocalDateTime.ofInstant(p.occurredAt, clock.zone),
                comment = p.comment.orEmpty(),
                original = p,
            )
        } else {
            form.update { it.copy(loaded = true, currency = settings.settings.first().inputCurrency) }
        }
    }

    private fun buildState(f: PaidForMeForm, cats: List<Category>, people: List<Person>, current: CurrentRates?): PaidForMeEditUiState {
        val snapshot = if (f.original != null) f.original.rates else current?.snapshot
        val money = f.amount.toMoney(f.currency)
        return PaidForMeEditUiState(
            form = f,
            conversions = snapshot?.let { s -> CurrencyCode.entries.filter { it != f.currency }.mapNotNull { s.convertOrNull(money, it) } }.orEmpty(),
            ratesDate = snapshot?.date,
            categories = cats.filter { !it.archived || it.id == f.categoryId },
            persons = people.filter { !it.archived || it.id == f.personId },
        )
    }

    fun onKey(key: KeypadKey) = form.update { it.copy(amount = it.amount.press(key, it.currency.fractionDigits)) }

    fun onCurrency(currency: CurrencyCode) = form.update { it.copy(currency = currency, amount = it.amount.fitTo(currency)) }

    fun onCategory(id: Long) = form.update { it.copy(categoryId = id) }

    fun onPerson(id: Long) = form.update { it.copy(personId = id) }

    fun addPerson(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { onPerson(savePerson(0, name)) }
    }

    fun onDate(date: LocalDate) = form.update { it.copy(dateTime = LocalDateTime.of(date, it.dateTime.toLocalTime())) }

    fun onTime(time: LocalTime) = form.update { it.copy(dateTime = LocalDateTime.of(it.dateTime.toLocalDate(), time)) }

    fun onComment(text: String) = form.update { it.copy(comment = text) }

    fun save() {
        val s = state.value ?: return
        if (!s.canSave) return
        val f = s.form
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            save(
                PaidForMeInput(
                    id = id,
                    amount = f.amount.toMoney(f.currency),
                    personId = f.personId!!,
                    categoryId = f.categoryId!!,
                    occurredAt = occurredAt(f.dateTime, f.original?.occurredAt, clock),
                    comment = f.comment,
                ),
            )
            _close.send(Unit)
        }
    }
}

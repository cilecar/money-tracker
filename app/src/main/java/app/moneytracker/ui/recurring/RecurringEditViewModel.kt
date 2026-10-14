package app.moneytracker.ui.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.repository.PaymentMethodRepository
import app.moneytracker.domain.repository.RecurringRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.SaveRecurringUseCase
import app.moneytracker.ui.navigation.RecurringEditRoute
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
import javax.inject.Inject

data class RecurringForm(
    val loaded: Boolean = false,
    val original: RecurringExpense? = null,
    val amountText: String = "",
    val currency: CurrencyCode = CurrencyCode.RSD,
    val categoryId: Long? = null,
    val paymentMethodId: Long? = null,
    val frequency: Frequency = Frequency.MONTHLY,
    val interval: Int = 1,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    val comment: String = "",
    val tagsText: String = "",
) {
    val amount: Money? get() = Money.parse(amountText, currency)?.takeIf { it.minor > 0 }
    val endBeforeStart: Boolean get() = endDate != null && endDate.isBefore(startDate)
    val valid: Boolean get() = amount != null && categoryId != null && paymentMethodId != null && !endBeforeStart
}

data class RecurringEditUiState(
    val form: RecurringForm,
    val categories: List<Category> = emptyList(),
    val paymentMethods: List<PaymentMethod> = emptyList(),
    val today: LocalDate,
)

@HiltViewModel
class RecurringEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RecurringRepository,
    categories: CategoryRepository,
    private val paymentMethods: PaymentMethodRepository,
    private val settings: SettingsRepository,
    private val save: SaveRecurringUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val id = savedStateHandle.toRoute<RecurringEditRoute>().id
    private val form = MutableStateFlow(RecurringForm(startDate = LocalDate.now(clock)))

    private val _close = Channel<Unit>(Channel.CONFLATED)
    val close = _close.receiveAsFlow()

    val state: StateFlow<RecurringEditUiState> = combine(
        form,
        categories.observeAll(includeArchived = true),
        paymentMethods.observeAll(includeArchived = true),
    ) { f, cats, methods ->
        RecurringEditUiState(
            form = f,
            categories = cats.filter { !it.archived || it.id == f.categoryId },
            paymentMethods = methods.filter { !it.archived || it.id == f.paymentMethodId },
            today = LocalDate.now(clock),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringEditUiState(form.value, today = LocalDate.now(clock)))

    init {
        viewModelScope.launch {
            val r = if (id != 0L) repository.get(id) else null
            form.value = if (r != null) {
                RecurringForm(
                    loaded = true,
                    original = r,
                    amountText = r.amount.amount.stripTrailingZeros().toPlainString(),
                    currency = r.amount.currency,
                    categoryId = r.categoryId,
                    paymentMethodId = r.paymentMethodId,
                    frequency = r.frequency,
                    interval = r.interval,
                    startDate = r.startDate,
                    endDate = r.endDate,
                    comment = r.comment.orEmpty(),
                    tagsText = r.tags.joinToString(", "),
                )
            } else {
                val s = settings.settings.first()
                val methods = paymentMethods.getAll(includeArchived = false)
                val method = methods.firstOrNull { it.id == s.defaultPaymentMethodId } ?: methods.firstOrNull()
                form.value.copy(loaded = true, paymentMethodId = method?.id, currency = method?.currency ?: s.inputCurrency)
            }
        }
    }

    fun update(transform: (RecurringForm) -> RecurringForm) = form.update(transform)

    fun onPaymentMethod(method: PaymentMethod) = form.update { it.copy(paymentMethodId = method.id, currency = method.currency ?: it.currency) }

    fun save() {
        val f = form.value
        val amount = f.amount ?: return
        if (!f.valid) return
        val template = RecurringExpense.create(
            amount = amount,
            categoryId = f.categoryId!!,
            paymentMethodId = f.paymentMethodId!!,
            frequency = f.frequency,
            interval = f.interval,
            startDate = f.startDate,
            endDate = f.endDate,
            comment = f.comment,
            tags = f.tagsText.split(','),
        ).copy(id = f.original?.id ?: 0, active = f.original?.active ?: true)
        viewModelScope.launch {
            save(template)
            _close.send(Unit)
        }
    }

    /** Уже созданные расходы остаются: у них просто обнулится ссылка на шаблон (FK SET NULL). */
    fun delete() {
        val original = form.value.original ?: return
        viewModelScope.launch {
            repository.delete(original.id)
            _close.send(Unit)
        }
    }
}

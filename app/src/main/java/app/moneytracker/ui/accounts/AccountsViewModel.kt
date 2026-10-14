package app.moneytracker.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.AccountType
import app.moneytracker.domain.model.AccountBalance
import app.moneytracker.domain.model.AccountUsage
import app.moneytracker.domain.model.AccountsTotal
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.usecase.ReconcileAccountUseCase
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.ArchiveAccountUseCase
import app.moneytracker.domain.usecase.DeleteAccountUseCase
import app.moneytracker.domain.usecase.AccountError
import app.moneytracker.domain.usecase.AccountResult
import app.moneytracker.domain.usecase.SaveAccountUseCase
import app.moneytracker.data.db.DefaultNames
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/** Состояние диалога редактирования (null — диалог закрыт). */
data class AccountDraft(
    val original: Account? = null,
    val name: String = "",
    val type: AccountType = AccountType.CARD,
    val currency: CurrencyCode = CurrencyCode.RSD,
    val openingBalanceText: String = "",
    val openingDate: LocalDate = LocalDate.now(),
    val error: AccountError? = null,
    val badAmount: Boolean = false,
)

/** Диалог сверки: посчитанный остаток и введённый фактический. */
data class ReconcileDraft(val balance: AccountBalance, val actualText: String = "") {
    val actual: Money? get() = parseSignedMoney(actualText, balance.account.currency)
    val difference: Money? get() = actual?.let { it - balance.balance }
}

/** Остаток может быть отрицательным (кредитка, перерасход): разрешаем ведущий минус. */
fun parseSignedMoney(text: String, currency: CurrencyCode): Money? {
    val t = text.trim()
    if (t.isEmpty()) return Money.zero(currency)
    val negative = t.startsWith('-') || t.startsWith('−')
    val body = if (negative) t.substring(1) else t
    val parsed = if (body.trim() == "0") Money.zero(currency) else Money.parse(body, currency) ?: return null
    return if (negative) -parsed else parsed
}

sealed interface PaymentDeleteDialog {
    data class Confirm(val method: Account) : PaymentDeleteDialog
    data class Move(val method: Account, val usage: AccountUsage, val targets: List<Account>) : PaymentDeleteDialog
    data class Blocked(val method: Account, val error: AccountError) : PaymentDeleteDialog
}

data class AccountsUiState(
    val active: List<AccountBalance> = emptyList(),
    val archived: List<AccountBalance> = emptyList(),
    val total: AccountsTotal? = null,
    val defaultId: Long? = null,
    val draft: AccountDraft? = null,
    val reconcile: ReconcileDraft? = null,
    val deleteDialog: PaymentDeleteDialog? = null,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val repository: AccountRepository,
    private val settings: SettingsRepository,
    private val save: SaveAccountUseCase,
    private val delete: DeleteAccountUseCase,
    private val archive: ArchiveAccountUseCase,
    private val reconcileAccount: ReconcileAccountUseCase,
    private val defaultNames: DefaultNames,
    rates: ExchangeRateRepository,
    private val clock: Clock,
) : ViewModel() {

    private val dialogs = MutableStateFlow(AccountsUiState())

    val state: StateFlow<AccountsUiState> = combine(
        repository.observeBalances(),
        settings.settings,
        rates.observeCurrent(),
        dialogs,
    ) { all, s, r, d ->
        d.copy(
            active = all.filter { !it.account.archived },
            archived = all.filter { it.account.archived },
            total = AccountsTotal.of(all, s.displayCurrency, r?.snapshot),
            // Если по умолчанию ничего не задано, фактически используется первый в списке.
            defaultId = s.defaultAccountId ?: all.firstOrNull { !it.account.archived }?.account?.id,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun openNew() {
        viewModelScope.launch {
            val currency = settings.settings.first().inputCurrency
            dialogs.update { it.copy(draft = AccountDraft(currency = currency, openingDate = LocalDate.now(clock))) }
        }
    }

    fun openEdit(method: Account) = dialogs.update {
        it.copy(
            draft = AccountDraft(
                original = method,
                name = defaultNames.resolve(method.defaultKey, method.name),
                type = method.type,
                currency = method.currency,
                openingBalanceText = method.openingBalance.takeIf { !it.isZero }?.amount?.toPlainString().orEmpty(),
                openingDate = method.openingDate,
            ),
        )
    }

    fun updateDraft(transform: (AccountDraft) -> AccountDraft) =
        dialogs.update { s -> s.copy(draft = s.draft?.let { transform(it).copy(error = null, badAmount = false) }) }

    fun closeDraft() = dialogs.update { it.copy(draft = null) }

    fun saveDraft() {
        val d = dialogs.value.draft ?: return
        val original = d.original
        val keepKey = original?.defaultKey != null &&
            d.name.trim().equals(defaultNames.resolve(original.defaultKey, original.name), ignoreCase = true)
        val opening = parseSignedMoney(d.openingBalanceText, d.currency)
            ?: return dialogs.update { it.copy(draft = d.copy(badAmount = true)) }
        val method = Account(
            id = original?.id ?: 0,
            name = if (keepKey) original!!.name else d.name,
            type = d.type,
            currency = d.currency,
            openingBalanceMinor = opening.minor,
            openingDate = d.openingDate,
            defaultKey = if (keepKey) original!!.defaultKey else null,
            sortOrder = original?.sortOrder ?: 0,
            archived = original?.archived ?: false,
        )
        viewModelScope.launch {
            when (val r = save(method)) {
                is AccountResult.Success -> closeDraft()
                is AccountResult.Error -> dialogs.update { it.copy(draft = it.draft?.copy(error = r.error)) }
            }
        }
    }

    fun requestDelete(method: Account) {
        viewModelScope.launch {
            val usage = delete.usage(method.id)
            val dialog = if (usage.transfers > 0) {
                PaymentDeleteDialog.Blocked(method, AccountError.HAS_TRANSFERS)
            } else if (usage.needsTarget) {
                PaymentDeleteDialog.Move(method, usage, repository.getAll(includeArchived = false).filter { it.id != method.id })
            } else {
                PaymentDeleteDialog.Confirm(method)
            }
            dialogs.update { it.copy(draft = null, deleteDialog = dialog) }
        }
    }

    fun dismissDelete() = dialogs.update { it.copy(deleteDialog = null) }

    fun delete(method: Account, moveTo: Long? = null) {
        viewModelScope.launch {
            when (val r = delete.invoke(method.id, moveTo)) {
                is AccountResult.Success -> dismissDelete()
                is AccountResult.Error -> dialogs.update { it.copy(deleteDialog = PaymentDeleteDialog.Blocked(method, r.error)) }
            }
        }
    }

    fun setArchived(method: Account, archived: Boolean) {
        viewModelScope.launch {
            when (val r = archive(method.id, archived)) {
                is AccountResult.Success -> dialogs.update { it.copy(draft = null, deleteDialog = null) }
                is AccountResult.Error -> dialogs.update { it.copy(draft = null, deleteDialog = PaymentDeleteDialog.Blocked(method, r.error)) }
            }
        }
    }

    fun openReconcile(balance: AccountBalance) = dialogs.update { it.copy(reconcile = ReconcileDraft(balance)) }

    fun updateReconcile(text: String) = dialogs.update { s -> s.copy(reconcile = s.reconcile?.copy(actualText = text)) }

    fun closeReconcile() = dialogs.update { it.copy(reconcile = null) }

    fun confirmReconcile(comment: String) {
        val d = dialogs.value.reconcile ?: return
        val actual = d.actual ?: return
        viewModelScope.launch {
            reconcileAccount(d.balance.account.id, actual, comment)
            closeReconcile()
        }
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.BalanceAdjustment
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.AccountUsage
import app.moneytracker.domain.repository.AccountRepository
import app.moneytracker.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject

enum class AccountError { EMPTY_NAME, DUPLICATE_NAME, LAST_ACTIVE, NEEDS_TARGET, INVALID_TARGET, CURRENCY_LOCKED }

sealed interface AccountResult {
    data class Success(val id: Long) : AccountResult
    data class Error(val error: AccountError) : AccountResult
}

class SaveAccountUseCase @Inject constructor(private val repository: AccountRepository) {
    suspend operator fun invoke(method: Account): AccountResult {
        val clean = method.copy(name = method.name.trim())
        if (clean.name.isEmpty()) return AccountResult.Error(AccountError.EMPTY_NAME)
        val duplicate = repository.getAll().any {
            it.id != clean.id && !it.archived && CategoryRules.sameName(it.name, clean.name)
        }
        if (duplicate) return AccountResult.Error(AccountError.DUPLICATE_NAME)
        if (clean.id != 0L) {
            val old = repository.get(clean.id)
            if (old != null && old.currency != clean.currency && repository.hasOperations(clean.id)) {
                return AccountResult.Error(AccountError.CURRENCY_LOCKED)
            }
        }
        val toSave = if (clean.id == 0L) clean.copy(sortOrder = repository.nextSortOrder()) else clean
        return AccountResult.Success(repository.upsert(toSave))
    }
}

class DeleteAccountUseCase @Inject constructor(
    private val repository: AccountRepository,
    private val settings: SettingsRepository,
) {
    suspend fun usage(id: Long): AccountUsage = repository.usage(id)

    suspend operator fun invoke(id: Long, moveTo: Long? = null): AccountResult {
        val all = repository.getAll()
        val method = all.firstOrNull { it.id == id } ?: return AccountResult.Success(id)
        if (!method.archived && all.count { !it.archived } <= 1) {
            return AccountResult.Error(AccountError.LAST_ACTIVE)
        }
        val usage = repository.usage(id)
        if (usage.needsTarget) {
            if (moveTo == null) return AccountResult.Error(AccountError.NEEDS_TARGET)
            if (all.none { it.id == moveTo && it.id != id && !it.archived }) {
                return AccountResult.Error(AccountError.INVALID_TARGET)
            }
        }
        repository.delete(id, moveTo.takeIf { usage.needsTarget })
        clearDefaultIf(id)
        return AccountResult.Success(id)
    }

    private suspend fun clearDefaultIf(id: Long) {
        if (settings.settings.first().defaultAccountId == id) settings.setDefaultAccount(null)
    }
}

class ArchiveAccountUseCase @Inject constructor(
    private val repository: AccountRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(id: Long, archived: Boolean): AccountResult {
        val all = repository.getAll()
        if (archived) {
            if (all.none { !it.archived && it.id != id }) {
                return AccountResult.Error(AccountError.LAST_ACTIVE)
            }
            // Архивный способ не может быть способом по умолчанию.
            if (settings.settings.first().defaultAccountId == id) settings.setDefaultAccount(null)
        } else {
            // Пока способ лежал в архиве, его имя могли занять.
            val method = all.firstOrNull { it.id == id } ?: return AccountResult.Success(id)
            if (all.any { it.id != id && !it.archived && CategoryRules.sameName(it.name, method.name) }) {
                return AccountResult.Error(AccountError.DUPLICATE_NAME)
            }
        }
        repository.setArchived(id, archived)
        return AccountResult.Success(id)
    }
}

/**
 * Сверка: пользователь вводит фактический остаток, разница с посчитанным записывается корректировкой.
 * Корректировка — не расход и не доход: аналитика и бюджеты её не видят.
 */
class ReconcileAccountUseCase @Inject constructor(
    private val repository: AccountRepository,
    private val clock: Clock,
) {
    /** @return созданная корректировка или null, если остаток уже совпадает. */
    suspend operator fun invoke(accountId: Long, actual: Money, comment: String? = null): BalanceAdjustment? {
        val current = requireNotNull(repository.observeBalances().first().firstOrNull { it.account.id == accountId }) {
            "Account $accountId not found"
        }
        require(actual.currency == current.account.currency) { "Balance must be in ${current.account.currency}" }
        val difference = actual - current.balance
        if (difference.isZero) return null
        val now = clock.instant()
        val adjustment = BalanceAdjustment(
            accountId = accountId,
            amount = difference,
            occurredAt = now,
            localDate = now.atZone(clock.zone).toLocalDate(),
            comment = comment?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = now,
        )
        return adjustment.copy(id = repository.addAdjustment(adjustment))
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.PaymentMethodUsage
import app.moneytracker.domain.repository.PaymentMethodRepository
import app.moneytracker.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

enum class PaymentMethodError { EMPTY_NAME, DUPLICATE_NAME, LAST_ACTIVE, NEEDS_TARGET, INVALID_TARGET }

sealed interface PaymentMethodResult {
    data class Success(val id: Long) : PaymentMethodResult
    data class Error(val error: PaymentMethodError) : PaymentMethodResult
}

class SavePaymentMethodUseCase @Inject constructor(private val repository: PaymentMethodRepository) {
    suspend operator fun invoke(method: PaymentMethod): PaymentMethodResult {
        val clean = method.copy(name = method.name.trim())
        if (clean.name.isEmpty()) return PaymentMethodResult.Error(PaymentMethodError.EMPTY_NAME)
        val duplicate = repository.getAll().any {
            it.id != clean.id && !it.archived && CategoryRules.sameName(it.name, clean.name)
        }
        if (duplicate) return PaymentMethodResult.Error(PaymentMethodError.DUPLICATE_NAME)
        val toSave = if (clean.id == 0L) clean.copy(sortOrder = repository.nextSortOrder()) else clean
        return PaymentMethodResult.Success(repository.upsert(toSave))
    }
}

class DeletePaymentMethodUseCase @Inject constructor(
    private val repository: PaymentMethodRepository,
    private val settings: SettingsRepository,
) {
    suspend fun usage(id: Long): PaymentMethodUsage = repository.usage(id)

    suspend operator fun invoke(id: Long, moveTo: Long? = null): PaymentMethodResult {
        val all = repository.getAll()
        val method = all.firstOrNull { it.id == id } ?: return PaymentMethodResult.Success(id)
        if (!method.archived && all.count { !it.archived } <= 1) {
            return PaymentMethodResult.Error(PaymentMethodError.LAST_ACTIVE)
        }
        val usage = repository.usage(id)
        if (usage.needsTarget) {
            if (moveTo == null) return PaymentMethodResult.Error(PaymentMethodError.NEEDS_TARGET)
            if (all.none { it.id == moveTo && it.id != id && !it.archived }) {
                return PaymentMethodResult.Error(PaymentMethodError.INVALID_TARGET)
            }
        }
        repository.delete(id, moveTo.takeIf { usage.needsTarget })
        clearDefaultIf(id)
        return PaymentMethodResult.Success(id)
    }

    private suspend fun clearDefaultIf(id: Long) {
        if (settings.settings.first().defaultPaymentMethodId == id) settings.setDefaultPaymentMethod(null)
    }
}

class ArchivePaymentMethodUseCase @Inject constructor(
    private val repository: PaymentMethodRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(id: Long, archived: Boolean): PaymentMethodResult {
        if (archived) {
            if (repository.getAll().none { !it.archived && it.id != id }) {
                return PaymentMethodResult.Error(PaymentMethodError.LAST_ACTIVE)
            }
            // Архивный способ не может быть способом по умолчанию.
            if (settings.settings.first().defaultPaymentMethodId == id) settings.setDefaultPaymentMethod(null)
        }
        repository.setArchived(id, archived)
        return PaymentMethodResult.Success(id)
    }
}

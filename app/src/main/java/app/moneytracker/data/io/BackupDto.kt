package app.moneytracker.data.io

import kotlinx.serialization.Serializable

/**
 * Формат JSON-выгрузки. Отдельные DTO, а не entity Room: формат файла — это контракт,
 * он не должен ломаться от переименования колонки в БД. Деньги — минорные единицы,
 * курсы — строки (BigDecimal без потерь), даты — ISO-8601.
 */
@Serializable
data class BackupDto(
    val format: String,
    val version: Int,
    val exportedAt: String,
    val settings: SettingsDto,
    val categories: List<CategoryDto>,
    val paymentMethods: List<PaymentMethodDto>,
    val expenses: List<ExpenseDto>,
    val budgets: List<BudgetDto>,
    val budgetAlerts: List<BudgetAlertDto> = emptyList(),
    val recurring: List<RecurringDto>,
    val exchangeRates: List<RateDto>,
) {
    companion object {
        const val FORMAT = "money-tracker-backup"
        const val VERSION = 1
    }
}

@Serializable
data class SettingsDto(
    val inputCurrency: String,
    val displayCurrency: String,
    val defaultPaymentMethodId: Long? = null,
    val themeMode: String,
    val dynamicColor: Boolean,
)

@Serializable
data class CategoryDto(
    val id: Long,
    val name: String,
    val defaultKey: String? = null,
    val iconKey: String,
    val color: Int,
    val parentId: Long? = null,
    val sortOrder: Int,
    val archived: Boolean,
)

@Serializable
data class PaymentMethodDto(
    val id: Long,
    val name: String,
    val type: String,
    val currency: String? = null,
    val defaultKey: String? = null,
    val sortOrder: Int,
    val archived: Boolean,
)

@Serializable
data class RatesDto(val rsdPerEur: String, val rubPerEur: String, val date: String)

@Serializable
data class ExpenseDto(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val paymentMethodId: Long,
    val occurredAt: String,
    val localDate: String,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val rates: RatesDto? = null,
    /** Для читаемости файла человеком; при импорте пересчитывается из rates. */
    val amountRsdMinor: Long? = null,
    val amountEurMinor: Long? = null,
    val amountRubMinor: Long? = null,
    val recurringId: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BudgetDto(val id: Long, val categoryId: Long, val limitMinor: Long, val currency: String)

@Serializable
data class BudgetAlertDto(val budgetId: Long, val yearMonth: String, val level: Int)

@Serializable
data class RecurringDto(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    val paymentMethodId: Long,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val frequency: String,
    val interval: Int,
    val dayOfMonth: Int? = null,
    val startDate: String,
    val endDate: String? = null,
    val nextRunDate: String,
    val active: Boolean,
)

@Serializable
data class RateDto(val date: String, val currency: String, val source: String, val perEur: String, val fetchedAt: String)

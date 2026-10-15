@file:OptIn(ExperimentalSerializationApi::class)

package app.moneytracker.data.io

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Формат JSON-выгрузки. Отдельные DTO, а не entity Room: формат файла — это контракт,
 * он не должен ломаться от переименования колонки в БД. Деньги — минорные единицы,
 * курсы — строки (BigDecimal без потерь), даты — ISO-8601.
 *
 * Версия 2: способы оплаты стали счетами. @JsonNames читает старые имена полей из файлов версии 1,
 * а недостающие поля (валюта, начальный остаток) достраивает импорт — так же, как миграция базы 1→2.
 *
 * Версия 3: валюта USDT и курс USDT в снапшотах ([RatesDto.usdtPerEur]). Файлы версий 1–2 читаются как есть
 * (курса USDT в них нет). Номер поднят, чтобы старое приложение отказалось от файла с USDT понятной ошибкой
 * «файл из более новой версии», а не «файл несогласован».
 *
 * Цифры карты у счёта и правила «мерчант → категория» (автозахват) — необязательные поля без смены версии:
 * старое приложение просто их не прочитает, данные при этом не искажаются. Захваченные покупки
 * в ожидании подтверждения в файл не попадают — это временные записи.
 */
@Serializable
data class BackupDto(
    val format: String,
    val version: Int,
    val exportedAt: String,
    val settings: SettingsDto,
    val categories: List<CategoryDto>,
    @JsonNames("paymentMethods") val accounts: List<AccountDto>,
    val expenses: List<ExpenseDto>,
    val budgets: List<BudgetDto>,
    val budgetAlerts: List<BudgetAlertDto> = emptyList(),
    val recurring: List<RecurringDto>,
    val exchangeRates: List<RateDto>,
    val balanceAdjustments: List<BalanceAdjustmentDto> = emptyList(),
    /** Пусто в старых файлах — тогда при импорте создаются категории доходов по умолчанию. */
    val incomeCategories: List<IncomeCategoryDto> = emptyList(),
    val incomes: List<IncomeDto> = emptyList(),
    val refunds: List<RefundDto> = emptyList(),
    val transfers: List<TransferDto> = emptyList(),
    val merchantRules: List<MerchantRuleDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "money-tracker-backup"
        const val VERSION = 3
    }
}

@Serializable
data class SettingsDto(
    val inputCurrency: String,
    val displayCurrency: String,
    @JsonNames("defaultPaymentMethodId") val defaultAccountId: Long? = null,
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
data class AccountDto(
    val id: Long,
    val name: String,
    val type: String,
    /** null только в версии 1 (наличные без валюты). */
    val currency: String? = null,
    val openingBalanceMinor: Long = 0,
    /** null только в версии 1. */
    val openingDate: String? = null,
    val defaultKey: String? = null,
    val sortOrder: Int,
    val archived: Boolean,
    val cardSuffix: String? = null,
)

@Serializable
data class MerchantRuleDto(val merchantKey: String, val categoryId: Long, val updatedAt: String)

@Serializable
data class BalanceAdjustmentDto(
    val id: Long,
    val accountId: Long,
    val amountMinor: Long,
    val occurredAt: String,
    val localDate: String,
    val comment: String? = null,
    val createdAt: String,
)

@Serializable
data class RatesDto(
    val rsdPerEur: String,
    val rubPerEur: String,
    val date: String,
    /** Нет в файлах до версии 3 и у записей, сделанных без курса USDT. */
    val usdtPerEur: String? = null,
)

@Serializable
data class ExpenseDto(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val categoryId: Long,
    @JsonNames("paymentMethodId") val accountId: Long,
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
    val accountAmountMinor: Long? = null,
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
    @JsonNames("paymentMethodId") val accountId: Long,
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

@Serializable
data class IncomeCategoryDto(
    val id: Long,
    val name: String,
    val defaultKey: String? = null,
    val iconKey: String,
    val color: Int,
    val sortOrder: Int,
    val archived: Boolean,
)

@Serializable
data class IncomeDto(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val accountId: Long,
    val categoryId: Long,
    val occurredAt: String,
    val localDate: String,
    val comment: String? = null,
    val rates: RatesDto? = null,
    val accountAmountMinor: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class RefundDto(
    val id: Long,
    val amountMinor: Long,
    val currency: String,
    val accountId: Long,
    val expenseId: Long? = null,
    val categoryId: Long,
    val occurredAt: String,
    val localDate: String,
    val comment: String? = null,
    val rates: RatesDto? = null,
    val accountAmountMinor: Long? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class TransferDto(
    val id: Long,
    val fromAccountId: Long,
    val toAccountId: Long,
    val fromAmountMinor: Long,
    val fromCurrency: String,
    val toAmountMinor: Long,
    val toCurrency: String,
    val feeExpenseId: Long? = null,
    val occurredAt: String,
    val localDate: String,
    val comment: String? = null,
    val apiRates: RatesDto? = null,
    val createdAt: String,
    val updatedAt: String,
)

package app.moneytracker.data.db

import app.moneytracker.data.db.dao.ExpenseWithTags
import app.moneytracker.data.db.entity.BalanceAdjustmentEntity
import app.moneytracker.data.db.entity.CategoryEntity
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.entity.ExpenseEntity
import app.moneytracker.data.db.entity.AccountEntity
import app.moneytracker.data.db.entity.IncomeCategoryEntity
import app.moneytracker.data.db.entity.IncomeEntity
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import app.moneytracker.data.db.entity.RefundEntity
import app.moneytracker.domain.model.BalanceAdjustment
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.ConvertedAmounts
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.ExchangeRate
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Income
import app.moneytracker.domain.model.IncomeCategory
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.Refund
import app.moneytracker.domain.model.Account
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.model.RecurringExpense

fun ExpenseWithTags.toDomain(): Expense = with(expense) {
    Expense(
        id = id,
        amount = Money(amountMinor, currency),
        categoryId = categoryId,
        accountId = accountId,
        occurredAt = occurredAt,
        localDate = localDate,
        comment = comment,
        tags = tags.map { it.name }.sortedBy { it.lowercase() },
        rates = if (rateRsdPerEur != null && rateRubPerEur != null && rateDate != null) {
            RateSnapshot(rateRsdPerEur, rateRubPerEur, rateDate)
        } else {
            null
        },
        converted = if (rsdMinor != null && eurMinor != null && rubMinor != null) {
            ConvertedAmounts(rsdMinor, eurMinor, rubMinor)
        } else {
            null
        },
        recurringId = recurringId,
        accountAmountMinor = accountAmountMinor,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun Expense.toEntity() = ExpenseEntity(
    id = id,
    amountMinor = amount.minor,
    currency = amount.currency,
    categoryId = categoryId,
    accountId = accountId,
    occurredAt = occurredAt,
    localDate = localDate,
    comment = comment,
    rateRsdPerEur = rates?.rsdPerEur,
    rateRubPerEur = rates?.rubPerEur,
    rateDate = rates?.date,
    rsdMinor = converted?.rsdMinor,
    eurMinor = converted?.eurMinor,
    rubMinor = converted?.rubMinor,
    recurringId = recurringId,
    accountAmountMinor = accountAmountMinor,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CategoryEntity.toDomain() = Category(id, name, defaultKey, iconKey, color, parentId, sortOrder, archived)

fun Category.toEntity() = CategoryEntity(id, name, defaultKey, iconKey, color, parentId, sortOrder, archived)

fun AccountEntity.toDomain() =
    Account(id, name, type, currency, openingBalanceMinor, openingDate, defaultKey, sortOrder, archived)

fun Account.toEntity() =
    AccountEntity(id, name, type, currency, openingBalanceMinor, openingDate, defaultKey, sortOrder, archived)

fun BalanceAdjustmentEntity.toDomain(currency: CurrencyCode) =
    BalanceAdjustment(id, accountId, Money(amountMinor, currency), occurredAt, localDate, comment, createdAt)

fun BalanceAdjustment.toEntity() =
    BalanceAdjustmentEntity(id, accountId, amount.minor, occurredAt, localDate, comment, createdAt)

fun ExchangeRateEntity.toDomain() = ExchangeRate(date, currency, perEur, source, fetchedAt)

fun RecurringExpenseEntity.toDomain() = RecurringExpense(
    id = id,
    amount = Money(amountMinor, currency),
    categoryId = categoryId,
    accountId = accountId,
    comment = comment,
    tags = tags,
    frequency = frequency,
    interval = interval,
    dayOfMonth = dayOfMonth,
    startDate = startDate,
    endDate = endDate,
    nextRunDate = nextRunDate,
    active = active,
)

fun RecurringExpense.toEntity() = RecurringExpenseEntity(
    id = id,
    amountMinor = amount.minor,
    currency = amount.currency,
    categoryId = categoryId,
    accountId = accountId,
    comment = comment,
    tags = tags,
    frequency = frequency,
    interval = interval,
    dayOfMonth = dayOfMonth,
    startDate = startDate,
    endDate = endDate,
    nextRunDate = nextRunDate,
    active = active,
)

private fun snapshot(rsd: java.math.BigDecimal?, rub: java.math.BigDecimal?, date: java.time.LocalDate?) =
    if (rsd != null && rub != null && date != null) RateSnapshot(rsd, rub, date) else null

private fun converted(rsd: Long?, eur: Long?, rub: Long?) =
    if (rsd != null && eur != null && rub != null) ConvertedAmounts(rsd, eur, rub) else null

fun IncomeCategoryEntity.toDomain() = IncomeCategory(id, name, defaultKey, iconKey, color, sortOrder, archived)

fun IncomeCategory.toEntity() = IncomeCategoryEntity(id, name, defaultKey, iconKey, color, sortOrder, archived)

fun IncomeEntity.toDomain() = Income(
    id = id,
    amount = Money(amountMinor, currency),
    accountId = accountId,
    categoryId = incomeCategoryId,
    occurredAt = occurredAt,
    localDate = localDate,
    comment = comment,
    rates = snapshot(rateRsdPerEur, rateRubPerEur, rateDate),
    converted = converted(rsdMinor, eurMinor, rubMinor),
    accountAmountMinor = accountAmountMinor,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Income.toEntity() = IncomeEntity(
    id = id,
    amountMinor = amount.minor,
    currency = amount.currency,
    accountId = accountId,
    incomeCategoryId = categoryId,
    occurredAt = occurredAt,
    localDate = localDate,
    comment = comment,
    rateRsdPerEur = rates?.rsdPerEur,
    rateRubPerEur = rates?.rubPerEur,
    rateDate = rates?.date,
    rsdMinor = converted?.rsdMinor,
    eurMinor = converted?.eurMinor,
    rubMinor = converted?.rubMinor,
    accountAmountMinor = accountAmountMinor,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun RefundEntity.toDomain() = Refund(
    id = id,
    amount = Money(amountMinor, currency),
    accountId = accountId,
    expenseId = expenseId,
    categoryId = categoryId,
    occurredAt = occurredAt,
    localDate = localDate,
    comment = comment,
    rates = snapshot(rateRsdPerEur, rateRubPerEur, rateDate),
    converted = converted(rsdMinor, eurMinor, rubMinor),
    accountAmountMinor = accountAmountMinor,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Refund.toEntity() = RefundEntity(
    id = id,
    amountMinor = amount.minor,
    currency = amount.currency,
    accountId = accountId,
    expenseId = expenseId,
    categoryId = categoryId,
    occurredAt = occurredAt,
    localDate = localDate,
    comment = comment,
    rateRsdPerEur = rates?.rsdPerEur,
    rateRubPerEur = rates?.rubPerEur,
    rateDate = rates?.date,
    rsdMinor = converted?.rsdMinor,
    eurMinor = converted?.eurMinor,
    rubMinor = converted?.rubMinor,
    accountAmountMinor = accountAmountMinor,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

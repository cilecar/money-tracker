package app.moneytracker.data.db

import app.moneytracker.data.db.dao.ExpenseWithTags
import app.moneytracker.data.db.entity.CategoryEntity
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.data.db.entity.ExpenseEntity
import app.moneytracker.data.db.entity.PaymentMethodEntity
import app.moneytracker.data.db.entity.RecurringExpenseEntity
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.ConvertedAmounts
import app.moneytracker.domain.model.ExchangeRate
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.RateSnapshot
import app.moneytracker.domain.model.RecurringExpense

fun ExpenseWithTags.toDomain(): Expense = with(expense) {
    Expense(
        id = id,
        amount = Money(amountMinor, currency),
        categoryId = categoryId,
        paymentMethodId = paymentMethodId,
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
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

fun Expense.toEntity() = ExpenseEntity(
    id = id,
    amountMinor = amount.minor,
    currency = amount.currency,
    categoryId = categoryId,
    paymentMethodId = paymentMethodId,
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
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun CategoryEntity.toDomain() = Category(id, name, defaultKey, iconKey, color, parentId, sortOrder, archived)

fun Category.toEntity() = CategoryEntity(id, name, defaultKey, iconKey, color, parentId, sortOrder, archived)

fun PaymentMethodEntity.toDomain() = PaymentMethod(id, name, type, currency, defaultKey, sortOrder, archived)

fun PaymentMethod.toEntity() = PaymentMethodEntity(id, name, type, currency, defaultKey, sortOrder, archived)

fun ExchangeRateEntity.toDomain() = ExchangeRate(date, currency, perEur, source, fetchedAt)

fun RecurringExpenseEntity.toDomain() = RecurringExpense(
    id = id,
    amount = Money(amountMinor, currency),
    categoryId = categoryId,
    paymentMethodId = paymentMethodId,
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
    paymentMethodId = paymentMethodId,
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

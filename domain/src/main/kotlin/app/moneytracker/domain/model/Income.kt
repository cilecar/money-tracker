package app.moneytracker.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Сумма в валюте ввода плюс суммы во всех валютах по снапшоту курса — общее у расхода, дохода и возврата.
 * На этом держатся итоги ([Total]) по любым операциям.
 */
interface RatedAmount {
    val amount: Money
    val converted: ConvertedAmounts?

    /** Сумма в [currency] для отчётов; без курса известна только сумма в исходной валюте. */
    fun amountIn(currency: CurrencyCode): Money? =
        converted?.takeIf { currency.isDisplay }?.get(currency) ?: amount.takeIf { it.currency == currency }
}

/** Категория дохода — отдельный от расходов набор (Зарплата, Фриланс, Подарок, Другое). */
data class IncomeCategory(
    val id: Long = 0,
    val name: String,
    /** См. [Category.defaultKey]. */
    val defaultKey: String? = null,
    val iconKey: String,
    val color: Int,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

data class Income(
    val id: Long = 0,
    override val amount: Money,
    val accountId: Long,
    val categoryId: Long,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val comment: String? = null,
    /** Снапшот курса — как у [Expense.rates]: null, пока курсов не было. */
    val rates: RateSnapshot?,
    override val converted: ConvertedAmounts?,
    /** Сколько зачислено на счёт в его валюте, если валюты дохода и счёта различаются. */
    val accountAmountMinor: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) : RatedAmount {
    fun withOccurredAt(instant: Instant, zone: ZoneId, now: Instant) =
        copy(occurredAt = instant, localDate = instant.atZone(zone).toLocalDate(), updatedAt = now)

    /** Как [Expense.withAmount]: без курса новой валюты в снапшоте операция снова ждёт курса. */
    fun withAmount(newAmount: Money, now: Instant) = rates.usableFor(newAmount.currency).let { usable ->
        copy(amount = newAmount, rates = usable, converted = usable?.convertAll(newAmount), accountAmountMinor = null, updatedAt = now)
    }

    fun withRates(newRates: RateSnapshot, now: Instant) =
        copy(rates = newRates, converted = newRates.convertAll(amount), updatedAt = now)

    companion object {
        fun create(
            amount: Money,
            accountId: Long,
            categoryId: Long,
            occurredAt: Instant,
            zone: ZoneId,
            rates: RateSnapshot?,
            now: Instant,
            comment: String? = null,
        ) = Income(
            amount = amount,
            accountId = accountId,
            categoryId = categoryId,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            comment = cleanComment(comment),
            rates = rates.usableFor(amount.currency),
            converted = rates.usableFor(amount.currency)?.convertAll(amount),
            createdAt = now,
            updatedAt = now,
        )
    }
}

/**
 * Возврат денег за покупку. В доходы не попадает: аналитика и бюджеты вычитают его из трат [categoryId]
 * в периоде даты возврата (так итоги сходятся с балансом счёта).
 */
data class Refund(
    val id: Long = 0,
    override val amount: Money,
    val accountId: Long,
    /** Исходный расход; null — возврат без привязки или расход удалён. */
    val expenseId: Long?,
    /** Категория расхода, траты которой уменьшает возврат. */
    val categoryId: Long,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val comment: String? = null,
    val rates: RateSnapshot?,
    override val converted: ConvertedAmounts?,
    val accountAmountMinor: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) : RatedAmount {
    fun withOccurredAt(instant: Instant, zone: ZoneId, now: Instant) =
        copy(occurredAt = instant, localDate = instant.atZone(zone).toLocalDate(), updatedAt = now)

    /** Как [Expense.withAmount]: без курса новой валюты в снапшоте операция снова ждёт курса. */
    fun withAmount(newAmount: Money, now: Instant) = rates.usableFor(newAmount.currency).let { usable ->
        copy(amount = newAmount, rates = usable, converted = usable?.convertAll(newAmount), accountAmountMinor = null, updatedAt = now)
    }

    fun withRates(newRates: RateSnapshot, now: Instant) =
        copy(rates = newRates, converted = newRates.convertAll(amount), updatedAt = now)

    /**
     * Возврат как «расход с минусом»: так аналитика и бюджеты учитывают его теми же формулами,
     * что и расходы (по дням, категориям, счетам). Только для расчётов — в списки не попадает.
     */
    fun asNegativeExpense() = Expense(
        id = -id,
        amount = -amount,
        categoryId = categoryId,
        accountId = accountId,
        occurredAt = occurredAt,
        localDate = localDate,
        rates = rates,
        converted = converted?.let { -it },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun create(
            amount: Money,
            accountId: Long,
            expenseId: Long?,
            categoryId: Long,
            occurredAt: Instant,
            zone: ZoneId,
            rates: RateSnapshot?,
            now: Instant,
            comment: String? = null,
        ) = Refund(
            amount = amount,
            accountId = accountId,
            expenseId = expenseId,
            categoryId = categoryId,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            comment = cleanComment(comment),
            rates = rates.usableFor(amount.currency),
            converted = rates.usableFor(amount.currency)?.convertAll(amount),
            createdAt = now,
            updatedAt = now,
        )
    }
}

/** Пустой комментарий не храним. */
fun cleanComment(comment: String?): String? = comment?.trim()?.takeIf { it.isNotEmpty() }

enum class OperationKind { EXPENSE, INCOME, REFUND, TRANSFER }

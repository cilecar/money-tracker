package app.moneytracker.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Expense(
    val id: Long = 0,
    /** Исходная сумма в валюте ввода. */
    val amount: Money,
    val categoryId: Long,
    val paymentMethodId: Long,
    val occurredAt: Instant,
    /** Календарный день расхода в часовом поясе пользователя — для группировки по дням. */
    val localDate: LocalDate,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Снапшот курсов на момент добавления. null — курсов ещё не было (первый запуск без сети):
     * расход сохраняется, а снапшот заполнит [app.moneytracker.domain.usecase.FillPendingRatesUseCase],
     * когда курсы появятся.
     */
    val rates: RateSnapshot?,
    /** Суммы во всех валютах по [rates]; null, пока rates == null. */
    val converted: ConvertedAmounts?,
    val recurringId: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Смена даты: пересчитываем localDate, снапшот курса не трогаем. */
    fun withOccurredAt(instant: Instant, zone: ZoneId, now: Instant): Expense = copy(
        occurredAt = instant,
        localDate = instant.atZone(zone).toLocalDate(),
        updatedAt = now,
    )

    /** Смена суммы или валюты: пересчитываем converted по тому же снапшоту. */
    fun withAmount(newAmount: Money, now: Instant): Expense = copy(
        amount = newAmount,
        converted = rates?.convertAll(newAmount),
        updatedAt = now,
    )

    val hasRates: Boolean get() = rates != null

    /**
     * Сумма в [currency] для отчётов. Без курсов известна только сумма в исходной валюте,
     * для остальных валют — null (такие расходы считаются отдельно как «без курса»).
     */
    fun amountIn(currency: CurrencyCode): Money? =
        converted?.get(currency) ?: amount.takeIf { it.currency == currency }

    /** Явная правка курса этого расхода пользователем или заполнение отложенного снапшота. */
    fun withRates(newRates: RateSnapshot, now: Instant): Expense = copy(
        rates = newRates,
        converted = newRates.convertAll(amount),
        updatedAt = now,
    )

    companion object {
        fun create(
            amount: Money,
            categoryId: Long,
            paymentMethodId: Long,
            occurredAt: Instant,
            zone: ZoneId,
            rates: RateSnapshot?,
            now: Instant,
            comment: String? = null,
            tags: List<String> = emptyList(),
            recurringId: Long? = null,
        ) = Expense(
            amount = amount,
            categoryId = categoryId,
            paymentMethodId = paymentMethodId,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            comment = comment?.trim()?.takeIf { it.isNotEmpty() },
            tags = normalizeTags(tags),
            rates = rates,
            converted = rates?.convertAll(amount),
            recurringId = recurringId,
            createdAt = now,
            updatedAt = now,
        )

        /** Обрезает пробелы, убирает пустые и дубликаты без учёта регистра. */
        fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }
}

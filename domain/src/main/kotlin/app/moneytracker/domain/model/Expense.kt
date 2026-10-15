package app.moneytracker.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Expense(
    val id: Long = 0,
    /** Исходная сумма в валюте ввода. */
    override val amount: Money,
    val categoryId: Long,
    val accountId: Long,
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
    override val converted: ConvertedAmounts?,
    val recurringId: Long? = null,
    /**
     * Сколько списано со счёта в его валюте (минорные единицы), если валюты расхода и счёта различаются:
     * покупка 1 250 RSD с EUR-карты → 10.68 EUR по курсу банка. null — баланс считается по снапшоту [rates].
     */
    val accountAmountMinor: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) : RatedAmount {
    /** Смена даты: пересчитываем localDate, снапшот курса не трогаем. */
    fun withOccurredAt(instant: Instant, zone: ZoneId, now: Instant): Expense = copy(
        occurredAt = instant,
        localDate = instant.atZone(zone).toLocalDate(),
        updatedAt = now,
    )

    /**
     * Смена суммы или валюты: пересчитываем converted по тому же снапшоту, сумма списания устарела.
     * Если в снапшоте нет курса новой валюты (USDT в старом снапшоте), расход снова ждёт курса.
     */
    fun withAmount(newAmount: Money, now: Instant): Expense = copy(
        amount = newAmount,
        rates = rates.usableFor(newAmount.currency),
        converted = rates.usableFor(newAmount.currency)?.convertAll(newAmount),
        accountAmountMinor = null,
        updatedAt = now,
    )

    val hasRates: Boolean get() = rates != null

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
            accountId: Long,
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
            accountId = accountId,
            occurredAt = occurredAt,
            localDate = occurredAt.atZone(zone).toLocalDate(),
            comment = cleanComment(comment),
            tags = normalizeTags(tags),
            rates = rates.usableFor(amount.currency),
            converted = rates.usableFor(amount.currency)?.convertAll(amount),
            recurringId = recurringId,
            createdAt = now,
            updatedAt = now,
        )

        /** Обрезает пробелы, убирает пустые и дубликаты без учёта регистра. */
        fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }
}

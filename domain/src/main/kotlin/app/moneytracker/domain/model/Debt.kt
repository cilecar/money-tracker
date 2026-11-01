package app.moneytracker.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Человек, с которым делим покупки. С историей долгов не удаляется — архивируется. */
data class Person(
    val id: Long = 0,
    val name: String,
    val archived: Boolean = false,
    val sortOrder: Int = 0,
)

/**
 * Погашение долга: деньги пришли на счёт или ушли с него. Долг закрывается целиком; сумма — в валюте
 * счёта, сколько реально пришло или ушло. Разница курсов при погашении в другой валюте нигде не учитывается.
 */
data class Settlement(
    val accountId: Long,
    val amount: Money,
    val occurredAt: Instant,
    val localDate: LocalDate,
) {
    init {
        require(amount.minor > 0) { "Settlement amount must be positive" }
    }

    companion object {
        fun create(accountId: Long, amount: Money, occurredAt: Instant, zone: ZoneId) =
            Settlement(accountId, amount, occurredAt, occurredAt.atZone(zone).toLocalDate())
    }
}

/**
 * «Мне должны»: часть моего расхода, которую вернёт [personId]. Валюту, категорию, дату, счёт и курс
 * доля не хранит — берёт у расхода, поэтому правка расхода не требует синхронизации.
 */
data class ExpenseShare(
    val id: Long = 0,
    val expenseId: Long,
    val personId: Long,
    /** В валюте расхода. */
    val amount: Money,
    val settlement: Settlement? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isSettled: Boolean get() = settlement != null

    /**
     * Доля как «расход с минусом» в день покупки: аналитика и бюджеты вычитают её теми же формулами,
     * что и возвраты, и в категории остаётся только моя доля. В отличие от возврата — по дате расхода:
     * договорённость «трачу только свою часть» известна сразу, а не после погашения.
     */
    fun asNegativeExpense(expense: Expense): Expense {
        require(expense.id == expenseId) { "Share $id belongs to expense $expenseId, not ${expense.id}" }
        return Expense(
            id = -id,
            amount = -amount,
            categoryId = expense.categoryId,
            accountId = expense.accountId,
            occurredAt = expense.occurredAt,
            localDate = expense.localDate,
            rates = expense.rates,
            converted = expense.rates?.convertAll(amount)?.let { -it },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

/** Доля, введённая на экране разделения. */
data class ShareInput(val personId: Long, val amount: Money)

enum class ShareError { NOT_POSITIVE, WRONG_CURRENCY, DUPLICATE_PERSON, EXCEEDS_TOTAL }

/** null — доли годятся для расхода на [total]. Сумма долей может быть равна [total]: моя доля тогда 0. */
fun validateShares(total: Money, shares: List<ShareInput>): ShareError? {
    if (shares.any { it.amount.currency != total.currency }) return ShareError.WRONG_CURRENCY
    if (shares.any { it.amount.minor <= 0 }) return ShareError.NOT_POSITIVE
    if (shares.map { it.personId }.toSet().size != shares.size) return ShareError.DUPLICATE_PERSON
    val sum = shares.fold(Money.zero(total.currency)) { acc, s -> acc + s.amount }
    return if (sum > total) ShareError.EXCEEDS_TOTAL else null
}

/**
 * «Поровну»: [total] на меня и [people] человек, в минорных единицах. Возвращает суммы для других;
 * остаток от деления достаётся мне (1000.00 на троих: 333.33, 333.33, мне 333.34).
 */
fun splitEqually(total: Money, people: Int): List<Money> {
    require(people >= 0) { "people must be non-negative" }
    if (people == 0) return emptyList()
    val each = total.minor / (people + 1)
    return List(people) { Money(each, total.currency) }
}

/**
 * «За меня заплатили»: покупку (мою долю) оплатил [personId]. По сути расход без счёта: в аналитике и
 * бюджетах считается как расход, баланс не трогает — с моих счетов ничего не списано.
 */
data class PaidForMe(
    val id: Long = 0,
    val personId: Long,
    override val amount: Money,
    val categoryId: Long,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val comment: String? = null,
    /** Снапшот курса — как у [Expense.rates]: null, пока курсов не было. */
    val rates: RateSnapshot?,
    override val converted: ConvertedAmounts?,
    val settlement: Settlement? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) : RatedAmount {
    fun withOccurredAt(instant: Instant, zone: ZoneId, now: Instant) =
        copy(occurredAt = instant, localDate = instant.atZone(zone).toLocalDate(), updatedAt = now)

    /** Как [Expense.withAmount]: без курса новой валюты в снапшоте запись снова ждёт курса. */
    fun withAmount(newAmount: Money, now: Instant) = rates.usableFor(newAmount.currency).let { usable ->
        copy(amount = newAmount, rates = usable, converted = usable?.convertAll(newAmount), updatedAt = now)
    }

    fun withRates(newRates: RateSnapshot, now: Instant) =
        copy(rates = newRates, converted = newRates.convertAll(amount), updatedAt = now)

    /** Для расчётов трат: счёта нет, поэтому [NO_ACCOUNT] — аналитика выводит такие траты отдельным срезом. */
    fun asExpense() = Expense(
        id = -id,
        amount = amount,
        categoryId = categoryId,
        accountId = NO_ACCOUNT,
        occurredAt = occurredAt,
        localDate = localDate,
        rates = rates,
        converted = converted,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        /** id счёта у [asExpense]: настоящие id счетов начинаются с 1. */
        const val NO_ACCOUNT = 0L

        fun create(
            amount: Money,
            personId: Long,
            categoryId: Long,
            occurredAt: Instant,
            zone: ZoneId,
            rates: RateSnapshot?,
            now: Instant,
            comment: String? = null,
        ) = PaidForMe(
            personId = personId,
            amount = amount,
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

enum class DebtDirection { OWED_TO_ME, I_OWE }

/** Ссылка на долг: id доли или записи «за меня заплатили» — таблицы разные, id пересекаются. */
data class DebtRef(val direction: DebtDirection, val id: Long)

/** Долг в одном виде для обоих направлений — для экрана «Долги» и строк погашений в ленте. */
data class Debt(
    val ref: DebtRef,
    val personId: Long,
    val amount: Money,
    val categoryId: Long,
    /** Дата покупки. */
    val localDate: LocalDate,
    val comment: String?,
    /** Счёт расхода — для «мне должны»; по умолчанию погашение приходит на него. */
    val expenseAccountId: Long?,
    val settlement: Settlement?,
) {
    val isSettled: Boolean get() = settlement != null

    companion object {
        fun of(share: ExpenseShare, expense: Expense) = Debt(
            ref = DebtRef(DebtDirection.OWED_TO_ME, share.id),
            personId = share.personId,
            amount = share.amount,
            categoryId = expense.categoryId,
            localDate = expense.localDate,
            comment = expense.comment,
            expenseAccountId = expense.accountId,
            settlement = share.settlement,
        )

        fun of(p: PaidForMe) = Debt(
            ref = DebtRef(DebtDirection.I_OWE, p.id),
            personId = p.personId,
            amount = p.amount,
            categoryId = p.categoryId,
            localDate = p.localDate,
            comment = p.comment,
            expenseAccountId = null,
            settlement = p.settlement,
        )
    }
}

/**
 * Всё, что считается тратами, — одним списком расходов со знаком: расходы, возвраты (минус, в день возврата),
 * доли «мне должны» (минус, в день покупки), «за меня заплатили» (плюс). Аналитика, бюджеты и итоги
 * считают по нему теми же формулами, поэтому везде видна только моя доля.
 */
object Spending {
    /** Доля, чей расход не в [expenses] (другой период или фильтр), пропускается: она относится к дню покупки. */
    fun items(
        expenses: List<Expense>,
        refunds: List<Refund> = emptyList(),
        shares: List<ExpenseShare> = emptyList(),
        paidForMe: List<PaidForMe> = emptyList(),
    ): List<Expense> {
        val byId = if (shares.isEmpty()) emptyMap() else expenses.associateBy { it.id }
        return expenses +
            refunds.map(Refund::asNegativeExpense) +
            shares.mapNotNull { share -> byId[share.expenseId]?.let(share::asNegativeExpense) } +
            paidForMe.map(PaidForMe::asExpense)
    }
}

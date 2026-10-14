package app.moneytracker.domain.model

import java.time.Instant
import java.time.LocalDate

enum class AccountType { CASH, CARD, TRANSFER }

/**
 * Счёт: наличные, карта, перевод. Баланс не хранится — его считает SQL по операциям
 * (см. [AccountBalance]), поэтому правка или удаление старого расхода сразу видны в остатке.
 */
data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** Валюта счёта: в ней считается баланс; при выборе счёта подставляется как валюта ввода. */
    val currency: CurrencyCode,
    /** Остаток на начало дня [openingDate] в минорных единицах [currency]; может быть отрицательным. */
    val openingBalanceMinor: Long = 0,
    /** Операции раньше этой даты в баланс не входят: до неё остаток уже учтён в [openingBalance]. */
    val openingDate: LocalDate,
    /** См. [Category.defaultKey]. */
    val defaultKey: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
) {
    // Хранится как Long, а не Money: иначе copy(currency = …) создавал бы счёт с остатком в чужой валюте.
    val openingBalance: Money get() = Money(openingBalanceMinor, currency)
}

/**
 * Остаток счёта в его валюте. Операция в другой валюте вычитается по сумме списания, если она известна,
 * иначе — по снапшоту курса. Если нет ни того, ни другого, операция в [balance] не вошла и посчитана в [unconverted]:
 * UI показывает остаток как приблизительный.
 */
data class AccountBalance(val account: Account, val balance: Money, val unconverted: Int = 0) {
    val isExact: Boolean get() = unconverted == 0
}

/**
 * Корректировка после сверки: разница между фактическим и посчитанным остатком.
 * Меняет только баланс — в расходы, доходы и бюджеты не попадает.
 */
data class BalanceAdjustment(
    val id: Long = 0,
    val accountId: Long,
    /** Со знаком, в валюте счёта: «+» — денег оказалось больше, чем насчитано. */
    val amount: Money,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val comment: String? = null,
    val createdAt: Instant,
)

/**
 * Итог по активным счетам в [total].currency по текущим курсам.
 * [missingRates] — курсов нет, и счета в других валютах в итог не вошли; [exact] — ни один остаток не приблизительный.
 */
data class AccountsTotal(val total: Money, val missingRates: Boolean, val exact: Boolean) {
    companion object {
        fun of(balances: List<AccountBalance>, currency: CurrencyCode, rates: RateSnapshot?): AccountsTotal {
            val active = balances.filter { !it.account.archived }
            var total = Money.zero(currency)
            var missing = false
            for (b in active) {
                when {
                    b.balance.currency == currency -> total += b.balance
                    rates != null -> total += rates.convert(b.balance, currency)
                    else -> missing = true
                }
            }
            return AccountsTotal(total, missing, active.all { it.isExact })
        }
    }
}

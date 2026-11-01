package app.moneytracker.ui.expenses.edit

import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.ShareInput

/** Доля на экране разделения: сумма — как набрана; погашенную менять нельзя. */
data class ShareDraft(val personId: Long, val amountText: String, val settled: Boolean = false)

/** «1 000,5» → 1000.50; пусто, не число или лишние знаки после запятой — null. */
fun parseShareAmount(text: String, currency: CurrencyCode): Money? {
    val clean = text.filterNot { it.isWhitespace() }.replace(',', '.')
    val value = clean.toBigDecimalOrNull() ?: return null
    if (value.scale() > currency.fractionDigits) return null
    return Money.of(value, currency)
}

/** null — какая-то сумма не разобрана. */
fun List<ShareDraft>.toInputs(currency: CurrencyCode): List<ShareInput>? =
    map { d -> ShareInput(d.personId, parseShareAmount(d.amountText, currency) ?: return null) }

fun Money.toDraftText(): String = amount.stripTrailingZeros().toPlainString()

/**
 * «Поровну»: погашенные доли не трогаем, остаток после них делим на меня и неуплаченных
 * (остаток от деления — мне). 3000, А уже вернул 2500 → Б 250, мне 250.
 */
fun List<ShareDraft>.splitEqually(total: Money): List<ShareDraft> {
    val settled = filter { it.settled }.mapNotNull { parseShareAmount(it.amountText, total.currency) }
        .fold(Money.zero(total.currency)) { acc, m -> acc + m }
    val each = app.moneytracker.domain.model.splitEqually(total - settled, count { !it.settled }).firstOrNull() ?: return this
    return map { if (it.settled) it else it.copy(amountText = each.toDraftText()) }
}

/** Доли, отложенные при смене валюты расхода: вернётся валюта — вернутся и они. */
data class StashedShares(val currency: CurrencyCode, val shares: List<ShareDraft>)

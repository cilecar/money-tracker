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

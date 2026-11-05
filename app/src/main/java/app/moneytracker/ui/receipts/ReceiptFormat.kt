package app.moneytracker.ui.receipts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.Money
import app.moneytracker.domain.receipt.ItemUnit
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Названия единиц из ресурсов — чтобы форматирование оставалось чистой функцией. */
data class UnitNames(val kg: String, val pcs: String, val l: String) {
    fun of(unit: ItemUnit): String = when (unit) {
        ItemUnit.KG -> kg
        ItemUnit.PCS -> pcs
        ItemUnit.L -> l
    }
}

@Composable
fun unitNames() = UnitNames(stringResource(R.string.unit_kg), stringResource(R.string.unit_pcs), stringResource(R.string.unit_l))

/** Количество в тысячных → «0,534 кг», «1 шт», «1,5 л». */
fun formatQuantity(milli: Long, unit: ItemUnit, names: UnitNames, locale: Locale): String {
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3; minimumFractionDigits = 0 }
    return format.format(BigDecimal.valueOf(milli, 3)) + " " + names.of(unit)
}

/** «/кг», «/шт» — после цены за единицу. */
fun perUnitLabel(unit: ItemUnit, names: UnitNames): String = "/" + names.of(unit)

/** Цены из чеков всегда в динарах. */
fun rsd(minor: Long) = Money(minor, CurrencyCode.RSD)

/**
 * Ссылка с чека — в браузер. Без селектора её перехватило бы само приложение
 * (оно обрабатывает ссылки suf.purs.gov.rs) и вместо сайта снова открылся бы чек.
 */
fun browserIntent(url: String): android.content.Intent =
    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
        selector = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_APP_BROWSER)
    }

package app.moneytracker.widget

import android.content.Context
import app.moneytracker.domain.model.Total
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.RefundRepository
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.ui.format.formatMoney
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.YearMonth
import java.time.format.TextStyle
import javax.inject.Inject

data class WidgetData(val title: String, val total: String)

class WidgetDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenses: ExpenseRepository,
    private val refunds: RefundRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend fun load(): WidgetData {
        val month = YearMonth.now(clock)
        val currency = settings.settings.first().displayCurrency
        // Траты месяца за вычетом возвратов — как на главном экране.
        val total = Total.spending(
            expenses.observeBetween(month.atDay(1), month.atEndOfMonth()).first(),
            refunds.observeBetween(month.atDay(1), month.atEndOfMonth()).first(),
            currency,
        )
        val locale = context.resources.configuration.locales[0]
        val title = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
        return WidgetData(
            title = title,
            total = formatMoney(total.total, locale) + if (total.withoutRates > 0) " +?" else "",
        )
    }
}

package app.moneytracker.widget

import android.content.Context
import app.moneytracker.domain.model.Total
import app.moneytracker.domain.repository.DebtRepository
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
    private val debts: DebtRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend fun load(): WidgetData {
        val month = YearMonth.now(clock)
        val currency = settings.settings.first().displayCurrency
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        // Траты месяца за вычетом возвратов, только моя доля — как на главном экране.
        val total = Total.spending(
            expenses.observeBetween(from, to).first(),
            refunds.observeBetween(from, to).first(),
            currency,
            shares = debts.observeSharesBetween(from, to).first(),
            paidForMe = debts.observePaidForMeBetween(from, to).first(),
        )
        val locale = context.resources.configuration.locales[0]
        val title = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
        return WidgetData(
            title = title,
            total = formatMoney(total.total, locale) + if (total.withoutRates > 0) " +?" else "",
        )
    }
}

package app.moneytracker.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.moneytracker.domain.repository.DebtRepository
import app.moneytracker.domain.repository.ExpenseRepository
import app.moneytracker.domain.repository.RefundRepository
import app.moneytracker.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обновляет виджет при любом изменении расходов, возвратов, долей или «за меня заплатили» текущего месяца
 * или валюты отображения.
 * Живёт, пока жив процесс: все изменения данных происходят в процессе приложения
 * (UI, WorkManager), так что отдельный механизм оповещения не нужен. Смену месяца
 * без открытия приложения покрывает updatePeriodMillis в описании виджета.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val expenses: ExpenseRepository,
    private val refunds: RefundRepository,
    private val debts: DebtRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    fun start() {
        val month = YearMonth.now(clock)
        scope.launch {
            val from = month.atDay(1)
            val to = month.atEndOfMonth()
            combine(
                expenses.observeBetween(from, to),
                refunds.observeBetween(from, to),
                debts.observeSharesBetween(from, to),
                debts.observePaidForMeBetween(from, to),
                settings.settings.map { it.displayCurrency }.distinctUntilChanged(),
            ) { list, refundList, shares, paid, currency -> listOf(list, refundList, shares, paid, currency) }
                // Импорт CSV меняет сотни строк подряд — перерисовываем один раз в конце.
                .debounce(1_000)
                .collect { MonthTotalWidget().updateAll(context) }
        }
    }
}

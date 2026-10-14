package app.moneytracker.ui.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.Analytics
import app.moneytracker.domain.model.AnalyticsPeriod
import app.moneytracker.domain.model.AnalyticsReport
import app.moneytracker.domain.model.CategorySlice
import app.moneytracker.domain.model.Granularity
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.common.CurrencyMenu
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatMoney
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    onCategory: (categoryId: Long, from: LocalDate, to: LocalDate) -> Unit,
    onAccount: (methodId: Long, from: LocalDate, to: LocalDate) -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var rangeOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_analytics)) },
                actions = { CurrencyMenu(s.report?.currency, viewModel::setCurrency) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PeriodSelector(
                state = s,
                onType = { if (it == PeriodType.CUSTOM) rangeOpen = true else viewModel.setType(it) },
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
            )
            val report = s.report
            if (report == null) {
                Spacer(Modifier.height(200.dp))
            } else if (report.total.total.isZero && report.previousTotal.total.isZero) {
                ComingSoon(stringResource(R.string.analytics_empty), Modifier.height(240.dp))
            } else {
                SummaryCard(report)
                ChartCard(stringResource(R.string.analytics_by_time)) {
                    val labels = bucketLabels(report)
                    SpendingColumnChart(report.buckets.map { it.amount }, labels, labelSpacing(report))
                }
                CategoriesCard(report) { slice ->
                    slice.category?.let { onCategory(it.id, report.period.from, report.period.to) }
                }
                if (report.accounts.isNotEmpty()) {
                    ChartCard(stringResource(R.string.analytics_by_payment)) {
                        PaymentBars(report.accounts) { slice ->
                            slice.method?.let { onAccount(it.id, report.period.from, report.period.to) }
                        }
                    }
                }
                ComparisonCard(report)
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (rangeOpen) {
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = s.period.from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = s.period.to.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { rangeOpen = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null,
                    onClick = {
                        val from = picker.selectedStartDateMillis!!.toUtcDate()
                        viewModel.setCustom(from, picker.selectedEndDateMillis?.toUtcDate() ?: from)
                        rangeOpen = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { rangeOpen = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DateRangePicker(picker, Modifier.height(480.dp)) }
    }
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()


@Composable
private fun PeriodSelector(state: AnalyticsUiState, onType: (PeriodType) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val types = listOf(
                PeriodType.WEEK to R.string.period_week,
                PeriodType.MONTH to R.string.period_month,
                PeriodType.YEAR to R.string.period_year,
                PeriodType.CUSTOM to R.string.period_custom,
            )
            types.forEachIndexed { i, (type, label) ->
                SegmentedButton(
                    selected = state.type == type,
                    onClick = { onType(type) },
                    shape = SegmentedButtonDefaults.itemShape(i, types.size),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.period_previous)) }
            Text(
                periodLabel(state.period, currentLocale()),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext, enabled = state.canGoNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.period_next))
            }
        }
    }
}

@Composable
private fun SummaryCard(r: AnalyticsReport) {
    val locale = currentLocale()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.analytics_total), style = MaterialTheme.typography.labelLarge)
            Text(formatMoney(r.total.total, locale), style = MaterialTheme.typography.headlineMedium)
            r.changePercent?.let { change ->
                val text = stringResource(
                    if (r.previousToDate != null) R.string.analytics_change_to_date else R.string.analytics_change,
                    (if (change > 0) "+" else "") + change + "%",
                    formatMoney(r.comparisonBase, locale),
                )
                // Рост расходов — «плохо» (цвет ошибки), снижение — «хорошо».
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (change > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(R.string.analytics_per_day, formatMoney(r.averagePerDay, locale)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (r.total.withoutRates > 0) {
                Text(
                    pluralStringResource(R.plurals.expenses_without_rates, r.total.withoutRates, r.total.withoutRates),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CategoriesCard(r: AnalyticsReport, onOpen: (CategorySlice) -> Unit) {
    if (r.categories.isEmpty()) return
    val locale = currentLocale()
    var selectedId by rememberSaveable(r.period, r.currency) { mutableStateOf<Long?>(null) }
    val selected = r.categories.firstOrNull { it.category?.id == selectedId }
    val fallback = MaterialTheme.colorScheme.outline

    ChartCard(stringResource(R.string.analytics_by_category)) {
        DonutChart(
            segments = r.categories.map { DonutSegment(it.category?.id, it.share, it.category?.let { c -> Color(c.color) } ?: fallback) },
            selectedKey = selectedId,
            onSelect = { selectedId = it as Long? },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        ) {
            if (selected != null) {
                Text(
                    selected.category?.displayName() ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatMoney(selected.amount, locale), style = MaterialTheme.typography.titleLarge)
                Text("${(selected.share * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.analytics_total), style = MaterialTheme.typography.labelLarge)
                Text(formatMoney(r.total.total, locale), style = MaterialTheme.typography.titleLarge)
            }
        }
        // Легенда: тап по строке открывает расходы категории за период.
        r.categories.forEach { slice ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onOpen(slice) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            ) {
                CategoryAvatar(slice.category, size = 32.dp)
                Column(Modifier.weight(1f)) {
                    Text(slice.category?.displayName() ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${(slice.share * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatMoney(slice.amount, locale), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ComparisonCard(r: AnalyticsReport) {
    val locale = currentLocale()
    ChartCard(stringResource(R.string.analytics_comparison)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(MaterialTheme.colorScheme.primary, periodLabel(r.period, locale))
            Legend(MaterialTheme.colorScheme.outline, periodLabel(r.period.previous(), locale))
        }
        ComparisonLineChart(r.cumulative, r.previousCumulative, bucketLabels(r), labelSpacing(r))

        // Таблица по категориям: было → стало.
        val rows = r.categories.filter { it.previous.minor > 0 || it.amount.minor > 0 }
        rows.forEach { slice ->
            val change = Analytics.percentChange(slice.amount, slice.previous)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(slice.category?.displayName() ?: "—", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatMoney(slice.previous, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("→", style = MaterialTheme.typography.bodySmall)
                Text(formatMoney(slice.amount, locale), style = MaterialTheme.typography.bodyMedium)
                Text(
                    change?.let { (if (it > 0) "+" else "") + it + "%" } ?: stringResource(R.string.analytics_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        change == null || change > 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Подписи по оси X: день месяца, день недели или короткий месяц. */
@Composable
private fun bucketLabels(r: AnalyticsReport): List<String> {
    val locale = currentLocale()
    return remember(r.buckets, locale) {
        r.buckets.map { b ->
            when {
                r.period.granularity == Granularity.MONTH -> b.start.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
                r.period is AnalyticsPeriod.Week -> b.start.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                else -> b.start.dayOfMonth.toString()
            }
        }
    }
}

/** Сколько бакетов пропускать между подписями, чтобы они не налезали друг на друга. */
private fun labelSpacing(r: AnalyticsReport): Int = when {
    r.buckets.size <= 12 -> 1
    r.buckets.size <= 31 -> 5
    else -> r.buckets.size / 6
}

fun periodLabel(period: AnalyticsPeriod, locale: Locale): String = when (period) {
    is AnalyticsPeriod.Month -> period.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) } + " " + period.month.year
    is AnalyticsPeriod.Year -> period.year.toString()
    is AnalyticsPeriod.Week, is AnalyticsPeriod.Custom -> {
        val f = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        "${period.from.format(f)} – ${period.to.format(f)}"
    }
}


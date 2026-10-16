package app.moneytracker.ui.rates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.domain.model.RateSource
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.ui.common.AppSnackbarHost
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatDateTime
import app.moneytracker.ui.format.formatRate
import java.math.RoundingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatesScreen(onBack: () -> Unit, viewModel: RatesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var editing by remember { mutableStateOf<List<CurrencyCode>?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                RatesEvent.Updated -> resources.getString(R.string.rates_updated)
                is RatesEvent.Failed -> resources.getString(event.reason.messageRes())
            }
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rates_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.rates_refresh))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val current = state.current
            when {
                state.loading -> Unit
                current == null -> NoRates(
                    onRefresh = viewModel::refresh,
                    onManual = { editing = CurrentRates.QUOTED },
                )
                else -> RatesList(
                    current = current,
                    onEdit = { editing = listOf(it) },
                    onReset = viewModel::clearManual,
                )
            }
        }
    }

    editing?.let { currencies ->
        ManualRatesDialog(
            currencies = currencies,
            initial = currencies.mapNotNull { c ->
                state.current?.effective?.get(c)?.perEur?.let { c to it.stripTrailingZeros().toPlainString() }
            }.toMap(),
            onConfirm = {
                viewModel.setManual(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun NoRates(onRefresh: () -> Unit, onManual: () -> Unit) {
    // LazyColumn, чтобы pull-to-refresh работал и на пустом экране.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.rates_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.rates_empty_text))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh) { Text(stringResource(R.string.rates_refresh)) }
                    OutlinedButton(onClick = onManual) { Text(stringResource(R.string.rates_enter_manually)) }
                }
            }
        }
    }
}

@Composable
private fun RatesList(
    current: CurrentRates,
    onEdit: (CurrencyCode) -> Unit,
    onReset: (CurrencyCode) -> Unit,
) {
    val locale = currentLocale()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 4.dp)) {
                Text(
                    stringResource(R.string.rates_as_of, formatDate(current.snapshot.date, locale)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    current.lastSyncAt?.let { stringResource(R.string.rates_last_sync, formatDateTime(it, locale)) }
                        ?: stringResource(R.string.rates_never_synced),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(CurrentRates.RATED.size) { index ->
            val currency = CurrentRates.RATED[index]
            val rate = current.effective[currency]
            if (rate == null) {
                // Необязательная валюта (USDT) без курса: запасной API её не отдаёт — можно ввести вручную.
                RateCard(
                    title = "1 EUR",
                    value = "— ${currency.name}",
                    subtitle = stringResource(R.string.rates_missing),
                    onEdit = { onEdit(currency) },
                )
            } else {
                RateCard(
                    title = "1 EUR",
                    value = "${formatRate(rate.perEur, locale)} ${currency.name}",
                    subtitle = stringResource(rate.source.labelRes()) + " · " + formatDate(rate.date, locale),
                    manual = rate.source == RateSource.MANUAL,
                    onEdit = { onEdit(currency) },
                    onReset = { onReset(currency) },
                )
            }
        }
        // Производные курсы к динару: удобно, когда тратишь рубли или USDT в Сербии.
        val crosses = listOf(CurrencyCode.RUB, CurrencyCode.USDT).mapNotNull { from ->
            current.snapshot.rateOrNull(from, CurrencyCode.RSD)?.let { from to it.setScale(4, RoundingMode.HALF_EVEN) }
        }
        items(crosses.size) { index ->
            val (from, rate) = crosses[index]
            RateCard(
                title = "1 ${from.name}",
                value = "${formatRate(rate, locale)} RSD",
                subtitle = stringResource(R.string.rates_cross),
            )
        }
        item {
            Text(
                stringResource(R.string.rates_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun RateCard(
    title: String,
    value: String,
    subtitle: String,
    manual: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (manual) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.headlineSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (manual && onReset != null) {
                AssistChip(
                    onClick = onReset,
                    label = { Text(stringResource(R.string.rates_reset_manual)) },
                    leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                )
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rates_enter_manually))
                }
            }
        }
    }
}

fun RateSource.labelRes(): Int = when (this) {
    RateSource.MANUAL -> R.string.rates_source_manual
    RateSource.API_PRIMARY -> R.string.rates_source_primary
    RateSource.API_FALLBACK -> R.string.rates_source_fallback
}

fun RefreshResult.Failure.Reason.messageRes(): Int = when (this) {
    RefreshResult.Failure.Reason.NETWORK -> R.string.rates_error_network
    RefreshResult.Failure.Reason.BAD_RESPONSE -> R.string.rates_error_bad_response
}

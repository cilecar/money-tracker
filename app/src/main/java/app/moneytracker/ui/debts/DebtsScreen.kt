package app.moneytracker.ui.debts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.PersonBalance
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.operations.IncomeColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtsScreen(
    onBack: () -> Unit,
    onPerson: (Long) -> Unit,
    onAddPaidForMe: () -> Unit,
    viewModel: DebtsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debts_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPaidForMe,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.paid_for_me_new)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            state.summary?.let { summary ->
                item(key = "summary") {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.debts_owed_to_me), style = MaterialTheme.typography.labelLarge)
                                Text(formatMoney(summary.owedToMe, locale), style = MaterialTheme.typography.titleLarge, color = IncomeColor)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.debts_i_owe), style = MaterialTheme.typography.labelLarge)
                                Text(formatMoney(summary.iOwe, locale), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (summary.missingRates) {
                            Text(
                                stringResource(R.string.debts_missing_rates),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
                if (summary.people.isEmpty()) {
                    item(key = "empty") {
                        Text(stringResource(R.string.debts_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(summary.people, key = { "p-${it.person.id}" }) { b ->
                    ListItem(
                        modifier = Modifier.clickable { onPerson(b.person.id) },
                        headlineContent = { Text(b.person.name) },
                        supportingContent = { Text(balanceText(b)) },
                        trailingContent = {
                            Text(
                                formatMoney(if (b.net.minor < 0) -b.net else b.net, locale),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (b.net.minor > 0) IncomeColor else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
            }
            if (state.others.isNotEmpty()) {
                item(key = "others") {
                    Text(stringResource(R.string.debts_others), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                }
                items(state.others, key = { "o-${it.id}" }) { p ->
                    ListItem(modifier = Modifier.clickable { onPerson(p.id) }, headlineContent = { Text(p.name) })
                }
            }
            if (state.archived.isNotEmpty()) {
                item(key = "archived") {
                    Text(stringResource(R.string.debts_archived), Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                }
                items(state.archived, key = { "a-${it.id}" }) { p ->
                    ListItem(modifier = Modifier.clickable { onPerson(p.id) }, headlineContent = { Text(p.name) })
                }
            }
        }
    }
}

@Composable
private fun balanceText(b: PersonBalance): String {
    val base = when {
        b.net.minor > 0 -> stringResource(R.string.debts_owes_you)
        b.net.minor < 0 -> stringResource(R.string.debts_you_owe)
        else -> stringResource(R.string.debts_even)
    }
    val count = stringResource(R.string.debts_open_count, b.openCount)
    return listOfNotNull(base, count, stringResource(R.string.debts_missing_rates_short).takeIf { b.missingRates }).joinToString(" · ")
}


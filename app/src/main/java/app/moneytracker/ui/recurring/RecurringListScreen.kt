package app.moneytracker.ui.recurring

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDate
import app.moneytracker.ui.format.formatMoney

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringListScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: RecurringListViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val locale = currentLocale()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recurring_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(0) }) { Icon(Icons.Default.Add, stringResource(R.string.recurring_new)) }
        },
    ) { padding ->
        if (!s.loading && s.items.isEmpty()) {
            ComingSoon(stringResource(R.string.recurring_empty), Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(s.items, key = { it.id }) { r ->
                val category = s.categories[r.categoryId]
                val finished = r.endDate != null && r.nextRunDate.isAfter(r.endDate)
                ListItem(
                    modifier = Modifier.clickable { onEdit(r.id) },
                    leadingContent = { CategoryAvatar(category) },
                    headlineContent = {
                        Text(r.comment ?: category?.displayName() ?: "—", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column {
                            Text(scheduleText(r))
                            Text(
                                when {
                                    finished -> stringResource(R.string.recurring_finished)
                                    !r.active -> stringResource(R.string.recurring_paused)
                                    else -> stringResource(R.string.recurring_next, formatDate(r.nextRunDate, locale))
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(formatMoney(r.amount, locale), style = MaterialTheme.typography.titleSmall)
                            if (!finished) {
                                Switch(checked = r.active, onCheckedChange = { viewModel.setActive(r, it) })
                            }
                        }
                    },
                )
            }
        }
    }
}

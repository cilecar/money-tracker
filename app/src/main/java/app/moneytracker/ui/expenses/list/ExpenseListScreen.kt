package app.moneytracker.ui.expenses.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.DaySection
import app.moneytracker.domain.model.ExpenseDetails
import app.moneytracker.domain.model.Total
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.common.CurrencyMenu
import app.moneytracker.ui.common.categoryTitle
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatMoney
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    /** Задан — экран открыт из аналитики: показываем «назад» и заголовок фильтра. */
    onBack: (() -> Unit)? = null,
    viewModel: ExpenseListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var filterOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExpenseListEvent.Deleted -> scope.launch {
                    // Предыдущий Snackbar закрываем: «Отменить» должно относиться к последнему удалению.
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(
                        message = resources.getString(R.string.expense_deleted),
                        actionLabel = resources.getString(R.string.action_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restore(event.expense)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            if (searching) {
                SearchBar(
                    query = query,
                    onQuery = viewModel::setQuery,
                    onClose = {
                        searching = false
                        viewModel.setQuery("")
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(state.title ?: stringResource(R.string.nav_expenses), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        IconButton(onClick = { filterOpen = true }) {
                            BadgedBox(badge = { if (state.filter.activeCount > 0) Badge { Text("${state.filter.activeCount}") } }) {
                                Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.action_filter))
                            }
                        }
                        CurrencyMenu(state.displayCurrency, viewModel::setDisplayCurrency)
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.expense_new))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item(key = "summary") {
                SummaryCard(state, onClearFilter = viewModel::clearFilter)
            }
            if (state.sections.isEmpty()) {
                item(key = "empty") {
                    ComingSoon(
                        stringResource(if (state.filteredTotal != null) R.string.expenses_not_found else R.string.expenses_empty),
                        Modifier.padding(top = 48.dp),
                    )
                }
            }
            state.sections.forEach { section ->
                stickyHeader(key = "day-${section.date}") { DayHeader(section) }
                items(section.items, key = { it.expense.id }) { item ->
                    SwipeToDelete(onDelete = { viewModel.delete(item.expense.id) }) {
                        ExpenseRow(item, state.displayCurrency, onClick = { onEdit(item.expense.id) })
                    }
                }
            }
        }
    }

    if (filterOpen) {
        FilterSheet(
            state = state,
            onApply = {
                viewModel.setFilter(it)
                filterOpen = false
            },
            onDismiss = { filterOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_close))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, stringResource(R.string.action_clear)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}


@Composable
private fun SummaryCard(state: ExpenseListUiState, onClearFilter: () -> Unit) {
    val locale = currentLocale()
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            val filtered = state.filteredTotal
            if (filtered != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.expenses_found, state.filteredCount, state.filteredCount),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    InputChip(
                        selected = false,
                        onClick = onClearFilter,
                        label = { Text(stringResource(R.string.filter_reset)) },
                        trailingIcon = { Icon(Icons.Default.Close, null) },
                    )
                }
                TotalText(filtered)
            } else {
                val monthName = state.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
                    .replaceFirstChar { it.titlecase(locale) }
                Text(monthName, style = MaterialTheme.typography.labelLarge)
                state.monthTotal?.let { TotalText(it) }
            }
        }
    }
}

@Composable
private fun TotalText(total: Total) {
    val locale = currentLocale()
    Text(formatMoney(total.total, locale), style = MaterialTheme.typography.headlineMedium)
    if (total.withoutRates > 0) {
        Text(
            pluralStringResource(R.plurals.expenses_without_rates, total.withoutRates, total.withoutRates),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun DayHeader(section: DaySection) {
    val locale = currentLocale()
    val today = LocalDate.now()
    val label = when (section.date) {
        today -> stringResource(R.string.day_today)
        today.minusDays(1) -> stringResource(R.string.day_yesterday)
        else -> section.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
            .replaceFirstChar { it.titlecase(locale) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            formatMoney(section.total.total, locale) + if (section.total.withoutRates > 0) " +?" else "",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpenseRow(item: ExpenseDetails, displayCurrency: CurrencyCode, onClick: () -> Unit) {
    val locale = currentLocale()
    val e = item.expense
    val time = e.occurredAt.atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    val details = listOfNotNull(
        time,
        item.paymentMethod?.displayName(),
        e.comment,
        e.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
    ).joinToString(" · ")

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { CategoryAvatar(item.category) },
        headlineContent = { Text(categoryTitle(item.category, item.parentCategory), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(formatMoney(e.amount, locale), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (e.amount.currency != displayCurrency) {
                    Text(
                        e.amountIn(displayCurrency)?.let { formatMoney(it, locale, approx = true) } ?: stringResource(R.string.expense_no_rate_short),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            val color by animateColorAsState(
                if (state.targetValue == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                label = "swipe",
            )
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Box(Modifier.background(ListItemDefaults.containerColor)) { content() }
    }
}


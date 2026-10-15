package app.moneytracker.ui.budgets

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.common.categoryTitle
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatMoney
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(viewModel: BudgetsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Разрешение на уведомления просим в момент, когда оно понятно зачем, — при сохранении бюджета.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val requestNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_budgets)) }) },
        floatingActionButton = {
            if (s.freeCategories.isNotEmpty()) {
                FloatingActionButton(onClick = viewModel::openNew) { Icon(Icons.Default.Add, stringResource(R.string.budget_new)) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val locale = currentLocale()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.period_previous))
                    }
                    Text(
                        s.month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + s.month.year,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::nextMonth, enabled = s.canGoNext) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.period_next))
                    }
                }
            }
            if (!s.loading && s.items.isEmpty()) {
                item { ComingSoon(stringResource(R.string.budgets_empty), Modifier.padding(top = 48.dp)) }
            }
            items(s.items, key = { it.budget.id }) { p ->
                BudgetCard(p, s, onClick = { viewModel.openEdit(p.budget) })
            }
        }
    }

    s.draft?.let { d ->
        BudgetDialog(
            draft = d,
            state = s,
            onChange = viewModel::updateDraft,
            onSave = {
                viewModel.save()
                requestNotifications()
            },
            onDelete = viewModel::delete,
            onDismiss = viewModel::closeDraft,
        )
    }
}

@Composable
private fun BudgetCard(p: BudgetProgress, s: BudgetsUiState, onClick: () -> Unit) {
    val locale = currentLocale()
    val color = when (p.level) {
        BudgetLevel.OK -> MaterialTheme.colorScheme.primary
        BudgetLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        BudgetLevel.EXCEEDED -> MaterialTheme.colorScheme.error
    }
    val parent = p.category?.parentId?.let { id -> s.allCategories.firstOrNull { it.id == id } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CategoryAvatar(p.category, size = 36.dp)
                Text(categoryTitle(p.category, parent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${(p.ratio * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = color)
            }
            LinearProgressIndicator(
                progress = { p.ratio.coerceIn(0f, 1f) },
                color = color,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text(
                stringResource(R.string.budget_spent_of, formatMoney(p.spent, locale), formatMoney(p.limit, locale)),
                style = MaterialTheme.typography.bodyMedium,
            )
            val remaining = p.remaining
            Text(
                if (remaining.minor >= 0) {
                    val allowance = p.dailyAllowance(s.month, s.today)
                    stringResource(R.string.budget_remaining, formatMoney(remaining, locale)) +
                        (allowance?.let { " · " + stringResource(R.string.budget_per_day, formatMoney(it, locale)) } ?: "")
                } else {
                    stringResource(R.string.budget_over, formatMoney(-remaining, locale))
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (remaining.minor >= 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            if (p.withoutRates > 0) {
                Text(
                    pluralStringResource(R.plurals.expenses_without_rates, p.withoutRates, p.withoutRates),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDialog(
    draft: BudgetDraft,
    state: BudgetsUiState,
    onChange: ((BudgetDraft) -> BudgetDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // При редактировании категорию не меняем: это был бы уже другой бюджет.
    val choices = if (draft.existing != null) state.allCategories.filter { it.id == draft.categoryId } else state.freeCategories
    val byId = state.allCategories.associateBy { it.id }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (draft.existing == null) R.string.budget_new else R.string.budget_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val selected = draft.categoryId?.let(byId::get)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (draft.existing == null) expanded = it }) {
                    OutlinedTextField(
                        value = selected?.let { categoryTitle(it, it.parentId?.let(byId::get)) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        enabled = draft.existing == null,
                        label = { Text(stringResource(R.string.budget_category)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, draft.existing == null),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        choices.forEach { c ->
                            DropdownMenuItem(
                                leadingIcon = { CategoryAvatar(c, size = 24.dp) },
                                text = { Text(categoryTitle(c, c.parentId?.let(byId::get))) },
                                onClick = {
                                    onChange { it.copy(categoryId = c.id) }
                                    expanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { v -> onChange { it.copy(amountText = v) } },
                    label = { Text(stringResource(R.string.budget_limit)) },
                    singleLine = true,
                    isError = draft.amountText.isNotBlank() && draft.parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    // Бюджет считается по суммам в валютах аналитики — USDT среди них нет.
                    CurrencyCode.DISPLAY.forEachIndexed { i, c ->
                        SegmentedButton(
                            selected = draft.currency == c,
                            onClick = { onChange { it.copy(currency = c) } },
                            shape = SegmentedButtonDefaults.itemShape(i, CurrencyCode.DISPLAY.size),
                        ) { Text(c.name) }
                    }
                }
                Text(
                    stringResource(R.string.budget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.existing != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = draft.valid) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}


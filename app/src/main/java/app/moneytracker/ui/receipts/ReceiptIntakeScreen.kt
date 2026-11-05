package app.moneytracker.ui.receipts

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.Category
import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.usecase.ReceiptCandidate
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDateTime
import app.moneytracker.ui.format.formatMoney
import app.moneytracker.ui.navigation.ExpenseEditRoute

/** После скана: загрузка чека и выбор, к какому расходу его прикрепить. */
@Composable
fun ReceiptIntakeScreen(
    onBack: () -> Unit,
    onOpenReceipt: (Long) -> Unit,
    onOpenEditor: (ExpenseEditRoute) -> Unit,
    viewModel: ReceiptIntakeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is IntakeEvent.OpenReceipt -> {
                    if (e.duplicate) Toast.makeText(context, R.string.receipt_duplicate, Toast.LENGTH_SHORT).show()
                    onOpenReceipt(e.receiptId)
                }
                is IntakeEvent.OpenEditor -> onOpenEditor(e.route)
                IntakeEvent.Attached -> {
                    Toast.makeText(context, R.string.receipt_attached, Toast.LENGTH_SHORT).show()
                    onBack()
                }
            }
        }
    }
    ReceiptScaffold(stringResource(R.string.receipt_title), onBack) { padding ->
        when (val s = state) {
            IntakeUiState.Working -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.receipt_loading))
                }
            }
            is IntakeUiState.Message -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(s.text), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) { Text(stringResource(R.string.action_close)) }
                }
            }
            is IntakeUiState.Choose -> ChooseExpense(s, categories, padding, viewModel)
        }
    }
}

@Composable
private fun ChooseExpense(s: IntakeUiState.Choose, categories: Map<Long, Category>, padding: PaddingValues, viewModel: ReceiptIntakeViewModel) {
    val locale = currentLocale()
    LazyColumn(Modifier.fillMaxSize().padding(padding)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(if (s.matching.size == 1) R.string.receipt_found_title else R.string.receipt_found_many),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(formatMoney(s.total, locale), style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(s.matching, key = { c -> (if (c is ReceiptCandidate.OfCapture) "c" else "e") + c.id() }) { c ->
            when (c) {
                is ReceiptCandidate.OfExpense -> ExpenseRow(c.expense, categories, onClick = { viewModel.onAttach(c.expense.id) })
                is ReceiptCandidate.OfCapture -> ListItem(
                    modifier = Modifier.clickable { viewModel.onCapture(c.capture.id) },
                    headlineContent = { Text(formatMoney(c.capture.amount, locale) + " · " + c.capture.merchant) },
                    supportingContent = {
                        Text(formatDateTime(c.capture.occurredAt, locale) + " · " + stringResource(R.string.receipt_from_notification))
                    },
                )
            }
            HorizontalDivider()
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.matching.size == 1) {
                    val only = s.matching.single()
                    Button(onClick = {
                        when (only) {
                            is ReceiptCandidate.OfExpense -> viewModel.onAttach(only.expense.id)
                            is ReceiptCandidate.OfCapture -> viewModel.onCapture(only.capture.id)
                        }
                    }) { Text(stringResource(R.string.receipt_attach)) }
                }
                OutlinedButton(onClick = viewModel::onCreateNew) { Text(stringResource(R.string.receipt_create_new)) }
                if (!s.showSameDay) TextButton(onClick = viewModel::onShowSameDay) { Text(stringResource(R.string.receipt_choose_other)) }
            }
        }
        if (s.showSameDay) {
            item { Text(stringResource(R.string.receipt_same_day_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
            if (s.sameDay.isEmpty()) {
                item { Text(stringResource(R.string.receipt_same_day_empty), modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(s.sameDay, key = { "d" + it.id }) { e ->
                ExpenseRow(e, categories, onClick = { viewModel.onAttach(e.id) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ExpenseRow(e: Expense, categories: Map<Long, Category>, onClick: () -> Unit) {
    val locale = currentLocale()
    val category = categories[e.categoryId]
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { CategoryAvatar(category) },
        headlineContent = { Text(formatMoney(e.amount, locale) + (category?.let { " · " + it.displayName() } ?: "")) },
        supportingContent = { Text(listOfNotNull(formatDateTime(e.occurredAt, locale), e.comment?.takeIf { it.isNotBlank() }).joinToString(" · ")) },
    )
}

private fun ReceiptCandidate.id(): Long = when (this) {
    is ReceiptCandidate.OfExpense -> expense.id
    is ReceiptCandidate.OfCapture -> capture.id
}

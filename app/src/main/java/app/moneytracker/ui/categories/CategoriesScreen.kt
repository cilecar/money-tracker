package app.moneytracker.ui.categories

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.ConfirmDialog
import app.moneytracker.ui.common.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    onEdit: (id: Long) -> Unit,
    onAdd: (parentId: Long) -> Unit,
    viewModel: CategoriesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var archiveExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.categories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(0) }) { Icon(Icons.Default.Add, stringResource(R.string.category_new)) }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
            state.active.forEach { node ->
                item(key = node.category.id) {
                    ListItem(
                        modifier = Modifier.clickable { onEdit(node.category.id) },
                        leadingContent = { CategoryAvatar(node.category) },
                        headlineContent = { Text(node.category.displayName()) },
                        supportingContent = if (node.children.isNotEmpty()) {
                            { Text(pluralStringResource(R.plurals.subcategories_count, node.children.size, node.children.size)) }
                        } else {
                            null
                        },
                        trailingContent = {
                            IconButton(onClick = { onAdd(node.category.id) }) {
                                Icon(Icons.Default.Add, stringResource(R.string.category_add_sub))
                            }
                        },
                    )
                }
                items(node.children, key = { it.id }) { child ->
                    ListItem(
                        modifier = Modifier.clickable { onEdit(child.id) }.padding(start = 40.dp),
                        leadingContent = { CategoryAvatar(child, size = 32.dp) },
                        headlineContent = { Text(child.displayName()) },
                    )
                }
            }

            if (state.archived.isNotEmpty()) {
                item(key = "archive-header") {
                    HorizontalDivider()
                    ListItem(
                        modifier = Modifier.clickable { archiveExpanded = !archiveExpanded },
                        headlineContent = {
                            Text(stringResource(R.string.archive_title, state.archived.size), style = MaterialTheme.typography.titleSmall)
                        },
                        trailingContent = { Icon(if (archiveExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
                    )
                }
                if (archiveExpanded) {
                    items(state.archived, key = { "a-${it.id}" }) { c ->
                        ListItem(
                            modifier = Modifier.clickable { onEdit(c.id) },
                            leadingContent = { CategoryAvatar(c, size = 32.dp) },
                            headlineContent = { Text(c.displayName(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = {
                                IconButton(onClick = { viewModel.restore(c.id) }) {
                                    Icon(Icons.Default.Unarchive, stringResource(R.string.action_restore))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    state.restoreError?.let { error ->
        ConfirmDialog(
            title = stringResource(R.string.action_restore),
            message = stringResource(error.messageRes()),
            confirm = stringResource(android.R.string.ok),
            onConfirm = viewModel::dismissRestoreError,
            onDismiss = viewModel::dismissRestoreError,
        )
    }
}

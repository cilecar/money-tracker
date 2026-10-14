package app.moneytracker.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.usecase.CategoryError
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.CategoryColors
import app.moneytracker.ui.common.CategoryIcons
import app.moneytracker.ui.common.ConfirmDialog
import app.moneytracker.ui.common.DeleteWithMoveDialog
import app.moneytracker.ui.common.MoveTarget
import app.moneytracker.ui.common.categoryTitle
import app.moneytracker.ui.common.displayName

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoryEditScreen(onClose: () -> Unit, viewModel: CategoryEditViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.close.collect { onClose() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (s.isNew) R.string.category_new else R.string.category_edit)) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
                actions = {
                    val original = s.original
                    if (original != null) {
                        IconButton(onClick = { viewModel.setArchived(!original.archived) }) {
                            Icon(
                                if (original.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                                stringResource(if (original.archived) R.string.action_restore else R.string.action_archive),
                            )
                        }
                        IconButton(onClick = viewModel::requestDelete) {
                            Icon(Icons.Default.Delete, stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CategoryAvatar(s.preview, size = 56.dp)
                    OutlinedTextField(
                        value = s.name,
                        onValueChange = viewModel::onName,
                        label = { Text(stringResource(R.string.category_name)) },
                        singleLine = true,
                        isError = s.error == CategoryError.EMPTY_NAME || s.error == CategoryError.DUPLICATE_NAME,
                        supportingText = s.error?.let { { Text(stringResource(it.messageRes())) } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                }

                ParentSelector(s, onParent = viewModel::onParent)

                Text(stringResource(R.string.category_color), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryColors.palette.forEach { c ->
                        ColorSwatch(c, selected = c == s.color, onClick = { viewModel.onColor(c) })
                    }
                }

                Text(stringResource(R.string.category_icon), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryIcons.all.forEach { (key, icon) ->
                        val selected = key == s.iconKey
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color(s.color) else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable { viewModel.onIcon(key) },
                        ) {
                            Icon(
                                icon,
                                contentDescription = key,
                                tint = if (selected) contentColorFor(s.color) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Button(onClick = viewModel::save, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    DeleteDialogs(s, viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentSelector(s: CategoryEditUiState, onParent: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = s.parents.firstOrNull { it.id == s.parentId }
    val enabled = !s.hasChildren
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected?.displayName() ?: stringResource(R.string.category_no_parent),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.category_parent)) },
            supportingText = when {
                s.hasChildren -> ({ Text(stringResource(R.string.category_parent_disabled)) })
                s.error == CategoryError.INVALID_PARENT -> ({ Text(stringResource(R.string.category_error_parent)) })
                else -> null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.category_no_parent)) },
                onClick = {
                    onParent(null)
                    expanded = false
                },
            )
            s.parents.forEach { p ->
                DropdownMenuItem(
                    leadingIcon = { CategoryAvatar(p, size = 24.dp) },
                    text = { Text(p.displayName()) },
                    onClick = {
                        onParent(p.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(color))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = contentColorFor(color))
    }
}

private fun contentColorFor(color: Int): Color =
    if (Color(color).luminance() > 0.5f) Color.Black.copy(alpha = 0.8f) else Color.White

@Composable
private fun DeleteDialogs(s: CategoryEditUiState, viewModel: CategoryEditViewModel) {
    val original = s.original ?: return
    val name = original.displayName()
    when (val d = s.deleteDialog) {
        null -> Unit
        is DeleteDialog.Confirm -> ConfirmDialog(
            title = stringResource(R.string.category_delete_title, name),
            message = if (d.removesBudget) stringResource(R.string.delete_budget_removed) else null,
            confirm = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete() },
            onDismiss = viewModel::dismissDialog,
        )
        is DeleteDialog.Move -> {
            val byId = d.targets.associateBy { it.id }
            DeleteWithMoveDialog(
                title = stringResource(R.string.category_delete_title, name),
                message = buildString {
                    append(pluralStringResource(R.plurals.delete_has_expenses, d.usage.expenses + d.usage.recurring, d.usage.expenses + d.usage.recurring))
                    if (d.usage.budgets > 0) append(' ').append(stringResource(R.string.delete_budget_removed))
                },
                targets = d.targets.map { t ->
                    MoveTarget(t.id, categoryTitle(t, t.parentId?.let(byId::get))) { CategoryAvatar(t, size = 28.dp) }
                },
                onMove = { viewModel.delete(it) },
                onArchive = if (original.archived) null else ({ viewModel.setArchived(true) }),
                onDismiss = viewModel::dismissDialog,
            )
        }
        is DeleteDialog.Blocked -> ConfirmDialog(
            title = stringResource(R.string.category_delete_title, name),
            message = stringResource(d.error.messageRes()),
            confirm = stringResource(if (d.error == CategoryError.HAS_CHILDREN && !original.archived) R.string.action_archive else android.R.string.ok),
            onConfirm = {
                if (d.error == CategoryError.HAS_CHILDREN && !original.archived) viewModel.setArchived(true) else viewModel.dismissDialog()
            },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

fun CategoryError.messageRes(): Int = when (this) {
    CategoryError.EMPTY_NAME -> R.string.error_empty_name
    CategoryError.DUPLICATE_NAME -> R.string.error_duplicate_name
    CategoryError.INVALID_PARENT -> R.string.category_error_parent
    CategoryError.HAS_CHILDREN -> R.string.category_error_has_children
    CategoryError.LAST_ACTIVE -> R.string.category_error_last
    CategoryError.NEEDS_TARGET, CategoryError.INVALID_TARGET -> R.string.delete_choose_target
}


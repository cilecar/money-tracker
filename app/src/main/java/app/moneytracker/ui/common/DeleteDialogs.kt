package app.moneytracker.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.moneytracker.R

data class MoveTarget(val id: Long, val label: String, val leading: (@Composable () -> Unit)? = null)

/**
 * Удаление записи, на которую ссылаются расходы: выбрать, куда их перенести,
 * или вместо удаления отправить в архив (история не меняется).
 */
@Composable
fun DeleteWithMoveDialog(
    title: String,
    message: String,
    targets: List<MoveTarget>,
    onMove: (Long) -> Unit,
    onArchive: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    targets.forEach { t ->
                        ListItem(
                            modifier = Modifier.selectable(selected = selected == t.id, onClick = { selected = t.id }),
                            leadingContent = t.leading,
                            headlineContent = { Text(t.label) },
                            trailingContent = { RadioButton(selected = selected == t.id, onClick = null) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onMove) }) {
                Text(stringResource(R.string.delete_move_and_delete))
            }
        },
        dismissButton = {
            Column {
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text(stringResource(R.string.action_archive)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}


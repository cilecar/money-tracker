package app.moneytracker.ui.data

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.repository.BackupFormatException
import app.moneytracker.domain.repository.CsvErrorReason

private enum class Pending { JSON, RESTORE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataScreen(onBack: () -> Unit, viewModel: DataViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by rememberSaveable { mutableStateOf<Pending?>(null) }

    // SAF: пользователь сам выбирает место — память телефона, Google Drive, флешку.
    // Приложению не нужны разрешения на доступ к файлам.
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(viewModel::exportCsv) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(viewModel::exportJson) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { it?.let(viewModel::backup) }
    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importCsv) }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::importJson) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::restore) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Section(stringResource(R.string.data_section_export))
            Item(Icons.Default.TableChart, R.string.data_export_csv, R.string.data_export_csv_hint, !s.busy) {
                exportCsv.launch(viewModel.fileName("csv"))
            }
            Item(Icons.Default.DataObject, R.string.data_export_json, R.string.data_export_json_hint, !s.busy) {
                exportJson.launch(viewModel.fileName("json"))
            }
            HorizontalDivider()

            Section(stringResource(R.string.data_section_import))
            Item(Icons.Default.FileUpload, R.string.data_import_csv, R.string.data_import_csv_hint, !s.busy) {
                importCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
            }
            Item(Icons.Default.FileDownload, R.string.data_import_json, R.string.data_import_json_hint, !s.busy) {
                confirm = Pending.JSON
            }
            HorizontalDivider()

            Section(stringResource(R.string.data_section_backup))
            Item(Icons.Default.Backup, R.string.data_backup, R.string.data_backup_hint, !s.busy) {
                backup.launch(viewModel.fileName("db"))
            }
            Item(Icons.Default.Restore, R.string.data_restore, R.string.data_restore_hint, !s.busy) {
                confirm = Pending.RESTORE
            }
        }
    }

    confirm?.let { pending ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.data_replace_title)) },
            text = { Text(stringResource(R.string.data_replace_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (pending) {
                        Pending.JSON -> importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        Pending.RESTORE -> restore.launch(arrayOf("*/*"))
                    }
                }) { Text(stringResource(R.string.data_choose_file)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    s.message?.let { message ->
        AlertDialog(
            // После восстановления базы закрыть диалог нельзя — только перезапуск.
            onDismissRequest = { if (message != DataMessage.Restored) viewModel.dismissMessage() },
            title = { Text(stringResource(if (message is DataMessage.Failed) R.string.data_failed else R.string.data_done)) },
            text = { Text(messageText(message)) },
            confirmButton = {
                TextButton(onClick = {
                    if (message == DataMessage.Restored) AppRestarter.restart(context) else viewModel.dismissMessage()
                }) {
                    Text(stringResource(if (message == DataMessage.Restored) R.string.data_restart else android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun messageText(message: DataMessage): String = when (message) {
    is DataMessage.Exported -> message.count
        ?.let { pluralStringResource(R.plurals.data_exported_expenses, it, it) }
        ?: stringResource(R.string.data_exported)
    is DataMessage.JsonImported -> stringResource(
        R.string.data_json_imported,
        message.result.expenses,
        message.result.categories,
        message.result.budgets,
        message.result.recurring,
    )
    is DataMessage.CsvImported -> buildString {
        val r = message.result
        append(pluralStringResource(R.plurals.data_csv_imported, r.imported, r.imported))
        if (r.duplicates > 0) append('\n').append(pluralStringResource(R.plurals.data_csv_duplicates, r.duplicates, r.duplicates))
        if (r.createdCategories > 0) append('\n').append(stringResource(R.string.data_csv_created_categories, r.createdCategories))
        if (r.createdAccounts > 0) append('\n').append(stringResource(R.string.data_csv_created_methods, r.createdAccounts))
        if (r.errors.isNotEmpty()) {
            append("\n\n").append(pluralStringResource(R.plurals.data_csv_errors, r.errors.size, r.errors.size))
            // Первые несколько — с номерами строк, чтобы было что поправить в таблице.
            r.errors.take(5).forEach { e ->
                append('\n').append(stringResource(R.string.data_csv_error_line, e.line, stringResource(e.reason.messageRes())))
            }
        }
    }
    DataMessage.Restored -> stringResource(R.string.data_restored)
    is DataMessage.Failed -> stringResource(
        when (message.reason) {
            BackupFormatException.Reason.NOT_A_BACKUP -> R.string.data_error_not_backup
            BackupFormatException.Reason.NEWER_VERSION -> R.string.data_error_newer
            BackupFormatException.Reason.INCONSISTENT -> R.string.data_error_inconsistent
            BackupFormatException.Reason.NO_HEADER -> R.string.data_error_no_header
            null -> R.string.data_error_io
        },
    )
}

private fun CsvErrorReason.messageRes(): Int = when (this) {
    CsvErrorReason.BAD_DATE -> R.string.csv_error_date
    CsvErrorReason.BAD_AMOUNT -> R.string.csv_error_amount
    CsvErrorReason.BAD_CURRENCY -> R.string.csv_error_currency
    CsvErrorReason.MISSING_CATEGORY -> R.string.csv_error_category
    CsvErrorReason.BAD_RATE -> R.string.csv_error_rate
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(icon: ImageVector, title: Int, hint: Int, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(hint)) },
    )
}

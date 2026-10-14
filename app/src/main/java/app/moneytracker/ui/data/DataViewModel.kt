package app.moneytracker.ui.data

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.moneytracker.domain.repository.BackupFormatException
import app.moneytracker.domain.repository.CsvImportResult
import app.moneytracker.domain.repository.DataTransfer
import app.moneytracker.domain.repository.JsonImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

sealed interface DataMessage {
    data class Exported(val count: Int?) : DataMessage
    data class CsvImported(val result: CsvImportResult) : DataMessage
    data class JsonImported(val result: JsonImportResult) : DataMessage
    /** База подменена — нужен перезапуск. */
    data object Restored : DataMessage
    data class Failed(val reason: BackupFormatException.Reason?) : DataMessage
}

data class DataUiState(val busy: Boolean = false, val message: DataMessage? = null)

@HiltViewModel
class DataViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transfer: DataTransfer,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state

    fun fileName(extension: String) = "money-tracker-${LocalDate.now(clock)}.$extension"

    fun exportCsv(uri: Uri) = write(uri) { DataMessage.Exported(transfer.exportCsv(it)) }
    fun exportJson(uri: Uri) = write(uri) { transfer.exportJson(it); DataMessage.Exported(null) }
    fun backup(uri: Uri) = write(uri) { transfer.backupDatabase(it); DataMessage.Exported(null) }

    fun importCsv(uri: Uri) = read(uri) { DataMessage.CsvImported(transfer.importCsv(it)) }
    fun importJson(uri: Uri) = read(uri) { DataMessage.JsonImported(transfer.importJson(it)) }
    fun restore(uri: Uri) = read(uri) { transfer.restoreDatabase(it); DataMessage.Restored }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // "wt" — перезаписать файл целиком: без "t" старый файл длиннее нового оставил бы мусорный хвост.
    private fun write(uri: Uri, block: suspend (OutputStream) -> DataMessage) = run {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { block(it) } ?: throw FileNotFoundException(uri.toString())
        }
    }

    private fun read(uri: Uri, block: suspend (InputStream) -> DataMessage) = run {
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { block(it) } ?: throw FileNotFoundException(uri.toString())
        }
    }

    private fun run(block: suspend () -> DataMessage) {
        if (_state.value.busy) return
        _state.value = DataUiState(busy = true)
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: BackupFormatException) {
                DataMessage.Failed(e.reason)
            } catch (e: IOException) {
                DataMessage.Failed(null)
            }
            _state.value = DataUiState(busy = false, message = message)
        }
    }
}

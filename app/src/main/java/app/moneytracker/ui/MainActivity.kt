package app.moneytracker.ui

import android.content.Intent
import app.moneytracker.domain.receipt.FiscalLinkParser
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.domain.model.CurrentRates
import app.moneytracker.ui.rates.ManualRatesDialog
import app.moneytracker.ui.navigation.AppRoot
import app.moneytracker.ui.theme.MoneyTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Запрос «сразу открыть добавление расхода» — от кнопки «+» на виджете. */
    private val openAddRequest = MutableStateFlow(false)

    /** «Изменить» в уведомлении автозахвата: открыть редактор с заполненной покупкой. */
    private val openCaptureRequest = MutableStateFlow(0L)

    /** Ссылка с фискального чека из «Поделиться». */
    private val openReceiptRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // После поворота экрана Intent тот же — не открываем добавление повторно.
        if (savedInstanceState == null) handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            MoneyTrackerTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                val openAdd by openAddRequest.collectAsStateWithLifecycle()
                val openCapture by openCaptureRequest.collectAsStateWithLifecycle()
                val openReceipt by openReceiptRequest.collectAsStateWithLifecycle()
                val receiptsEnabled by viewModel.receiptsEnabled.collectAsStateWithLifecycle()
                AppRoot(
                    openAdd = openAdd,
                    onOpenAddHandled = { openAddRequest.value = false },
                    openCapture = openCapture,
                    onOpenCaptureHandled = { openCaptureRequest.value = 0 },
                    openReceipt = openReceipt,
                    receiptsEnabled = receiptsEnabled,
                    onEnableReceipts = viewModel::enableReceipts,
                    onOpenReceiptHandled = { openReceiptRequest.value = null },
                )
                NoRatesPrompt(viewModel)
            }
        }
    }

    // singleTask: если приложение уже открыто, виджет приходит сюда, а не создаёт вторую копию.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_ADD_EXPENSE -> openAddRequest.value = true
            ACTION_EDIT_CAPTURE -> openCaptureRequest.value = intent.getLongExtra(EXTRA_CAPTURE_ID, 0)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let(FiscalLinkParser::findUrl)
                ?.let { openReceiptRequest.value = it }
        }
    }

    companion object {
        const val ACTION_ADD_EXPENSE = "app.moneytracker.action.ADD_EXPENSE"
        const val ACTION_EDIT_CAPTURE = "app.moneytracker.action.EDIT_CAPTURE"
        const val EXTRA_CAPTURE_ID = "capture_id"
    }
}

@Composable
private fun NoRatesPrompt(viewModel: MainViewModel) {
    val show by viewModel.showNoRatesPrompt.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    if (!show) return

    if (manual) {
        ManualRatesDialog(
            currencies = CurrentRates.QUOTED,
            initial = emptyMap(),
            message = stringResource(R.string.rates_manual_first_launch),
            onConfirm = viewModel::setManualRates,
            onDismiss = { manual = false },
        )
    } else {
        AlertDialog(
            onDismissRequest = viewModel::dismissNoRatesPrompt,
            title = { Text(stringResource(R.string.rates_prompt_title)) },
            text = { Text(stringResource(R.string.rates_prompt_text)) },
            confirmButton = {
                TextButton(onClick = { manual = true }) { Text(stringResource(R.string.rates_enter_manually)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNoRatesPrompt) { Text(stringResource(R.string.action_later)) }
            },
        )
    }
}

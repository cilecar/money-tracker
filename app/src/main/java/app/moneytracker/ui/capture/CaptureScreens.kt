package app.moneytracker.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.moneytracker.R
import app.moneytracker.ui.common.CategoryAvatar
import app.moneytracker.ui.common.ComingSoon
import app.moneytracker.ui.common.displayName
import app.moneytracker.ui.format.currentLocale
import app.moneytracker.ui.format.formatDateTime
import app.moneytracker.ui.format.formatMoney

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackScaffold(title: String, onBack: () -> Unit, actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_close)) } },
                actions = actions,
            )
        },
        content = content,
    )
}

/** Объяснение, системный доступ к уведомлениям и переключатели. Без явного включения сервис ничего не делает. */
@Composable
fun AutoCaptureScreen(onBack: () -> Unit, onPending: () -> Unit, onDebug: () -> Unit, viewModel: AutoCaptureViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Доступ выдают в системных настройках — перепроверяем, когда пользователь возвращается оттуда.
    var access by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        access = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val openAccessSettings = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

    BackScaffold(stringResource(R.string.capture_title), onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.capture_explain_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.capture_explain), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                stringResource(if (access) R.string.capture_access_granted else R.string.capture_access_missing),
                color = if (access) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = openAccessSettings) {
                Text(stringResource(if (access) R.string.capture_access_revoke else R.string.capture_access_open))
            }
            SwitchRow(stringResource(R.string.capture_enable), stringResource(R.string.capture_enable_hint), s.enabled, enabled = access || s.enabled) { on ->
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.setEnabled(on)
            }
            SwitchRow(stringResource(R.string.capture_debug), stringResource(R.string.capture_debug_hint), s.debug, enabled = access || s.debug, onChange = viewModel::setDebug)
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable(onClick = onPending),
                headlineContent = { Text(stringResource(R.string.capture_pending_title)) },
                trailingContent = { Text("${s.pending}") },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onDebug),
                headlineContent = { Text(stringResource(R.string.capture_debug_list)) },
                trailingContent = { Text("${s.debugCount}") },
            )
            Text(stringResource(R.string.capture_card_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** «Ожидают подтверждения»: сами в расходы не попадают. */
@Composable
fun PendingCapturesScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, viewModel: PendingCapturesViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val locale = currentLocale()
    BackScaffold(stringResource(R.string.capture_pending_title), onBack) { padding ->
        val list = items ?: return@BackScaffold
        if (list.isEmpty()) {
            ComingSoon(stringResource(R.string.capture_pending_empty), Modifier.padding(padding))
            return@BackScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(list, key = { it.purchase.id }) { item ->
                val p = item.purchase
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ListItem(
                        leadingContent = { CategoryAvatar(item.category) },
                        headlineContent = { Text(formatMoney(p.amount, locale) + " · " + p.merchant) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    formatDateTime(p.occurredAt, locale),
                                    item.category?.displayName() ?: stringResource(R.string.capture_choose_category),
                                    item.account?.displayName() ?: p.cardSuffix?.let { stringResource(R.string.capture_unknown_card, it) },
                                ).joinToString(" · "),
                            )
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
                        if (item.suggestion.canAddDirectly) Button(onClick = { viewModel.add(p.id) }) { Text(stringResource(R.string.capture_add)) }
                        OutlinedButton(onClick = { onEdit(p.id) }) { Text(stringResource(R.string.capture_edit)) }
                        TextButton(onClick = { viewModel.dismiss(p.id) }) { Text(stringResource(R.string.capture_dismiss)) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Сырые уведомления из белого списка — для сбора примеров парсеру. Только на устройстве. */
@Composable
fun CaptureDebugScreen(onBack: () -> Unit, viewModel: CaptureDebugViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    BackScaffold(
        stringResource(R.string.capture_debug_list),
        onBack,
        actions = {
            IconButton(onClick = { clipboard.setText(AnnotatedString(items.orEmpty().joinToString("\n\n") { it.describe() })) }) {
                Icon(Icons.Default.ContentCopy, stringResource(R.string.capture_copy_all))
            }
            IconButton(onClick = viewModel::clear) { Icon(Icons.Default.Delete, stringResource(R.string.action_clear)) }
        },
    ) { padding ->
        val list = items ?: return@BackScaffold
        if (list.isEmpty()) {
            ComingSoon(stringResource(R.string.capture_debug_empty), Modifier.padding(padding))
            return@BackScaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.key + it.postedAt }) { n ->
                Card {
                    Row(Modifier.padding(12.dp)) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(n.describe(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { clipboard.setText(AnnotatedString(n.describe())) }) {
                            Icon(Icons.Default.ContentCopy, stringResource(R.string.capture_copy))
                        }
                    }
                }
            }
        }
    }
}

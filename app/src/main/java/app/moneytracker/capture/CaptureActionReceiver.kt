package app.moneytracker.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.moneytracker.domain.usecase.ConfirmCaptureUseCase
import app.moneytracker.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Кнопка «Добавить» в уведомлении: расход создаётся сразу, без открытия приложения. */
@AndroidEntryPoint
class CaptureActionReceiver : BroadcastReceiver() {
    @Inject lateinit var confirm: ConfirmCaptureUseCase
    @Inject lateinit var notifications: CaptureNotifications

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADD) return
        val id = intent.getLongExtra(MainActivity.EXTRA_CAPTURE_ID, 0).takeIf { it != 0L } ?: return
        // goAsync: запись в базу дольше, чем живёт обычный onReceive.
        val pending = goAsync()
        scope.launch {
            try {
                if (confirm(id) != null) notifications.cancel(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Счёт или категорию успели удалить, база отказала — без обработчика это роняет процесс.
                // Уведомление остаётся: покупку можно добавить через «Изменить».
                Log.w(TAG, "Capture $id: confirm failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ADD = "app.moneytracker.action.CAPTURE_ADD"
        private const val TAG = "CaptureAction"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

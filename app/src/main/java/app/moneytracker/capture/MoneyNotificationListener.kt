package app.moneytracker.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.moneytracker.domain.capture.RawNotification
import app.moneytracker.domain.repository.SettingsRepository
import app.moneytracker.domain.usecase.CapturePurchaseUseCase
import app.moneytracker.domain.usecase.CaptureResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/**
 * Читает уведомления только из белого списка пакетов (сейчас — Google Wallet); остальные отбрасываются
 * до того, как из них что-то извлекается. Работает, только если автозахват или отладка включены
 * в настройках приложения, — системного доступа к уведомлениям для этого мало.
 */
@AndroidEntryPoint
class MoneyNotificationListener : NotificationListenerService() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var capture: CapturePurchaseUseCase
    @Inject lateinit var debugStore: NotificationDebugStore
    @Inject lateinit var notifications: CaptureNotifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in capture.packages) return
        // Сводка группы повторяет дочерние уведомления — разбирать её не нужно.
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val raw = sbn.toRaw()
        scope.launch {
            val s = settings.settings.first()
            if (s.captureDebug) debugStore.add(raw)
            if (!s.autoCapture) return@launch
            val result = capture(raw)
            if (result is CaptureResult.Captured) notifications.show(result.purchase, result.suggestion)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun StatusBarNotification.toRaw(): RawNotification {
        val extras = notification.extras
        return RawNotification(
            packageName = packageName,
            key = key,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = Instant.ofEpochMilli(postTime),
        )
    }
}

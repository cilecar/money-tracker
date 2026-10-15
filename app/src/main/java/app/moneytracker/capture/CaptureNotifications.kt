package app.moneytracker.capture

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.moneytracker.R
import app.moneytracker.data.db.DefaultNames
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.repository.CategoryRepository
import app.moneytracker.domain.usecase.CaptureSuggestion
import app.moneytracker.ui.MainActivity
import app.moneytracker.ui.format.formatMoney
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** «Расход 1 250 RSD · Maxi» с кнопками «Добавить» и «Изменить». */
class CaptureNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val categories: CategoryRepository,
    private val defaultNames: DefaultNames,
) {
    fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_capture), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.notification_channel_capture_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    suspend fun show(purchase: CapturedPurchase, suggestion: CaptureSuggestion) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // покупка всё равно лежит в «Ожидают подтверждения»
        }
        val locale = context.resources.configuration.locales[0]
        val category = suggestion.categoryId?.let { id -> categories.getAll().firstOrNull { it.id == id } }
        val text = category?.let { defaultNames.resolve(it.defaultKey, it.name) }
            ?: context.getString(R.string.capture_choose_category)
        val edit = PendingIntent.getActivity(
            context,
            purchase.id.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_EDIT_CAPTURE)
                .putExtra(MainActivity.EXTRA_CAPTURE_ID, purchase.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.capture_notification_title, formatMoney(purchase.amount, locale), purchase.merchant))
            .setContentText(text)
            .setContentIntent(edit)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        // «Добавить» в один тап — только когда известны и категория, и счёт (иначе только «Изменить»).
        if (suggestion.canAddDirectly) {
            val add = PendingIntent.getBroadcast(
                context,
                purchase.id.toInt(),
                Intent(context, CaptureActionReceiver::class.java)
                    .setAction(CaptureActionReceiver.ACTION_ADD)
                    .putExtra(MainActivity.EXTRA_CAPTURE_ID, purchase.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.capture_add), add)
        }
        builder.addAction(0, context.getString(R.string.capture_edit), edit)
        NotificationManagerCompat.from(context).notify(notificationId(purchase.id), builder.build())
    }

    fun cancel(captureId: Long) = NotificationManagerCompat.from(context).cancel(notificationId(captureId))

    private companion object {
        const val CHANNEL_ID = "capture"

        /** Отдельный диапазон: id уведомлений бюджетов — это id бюджетов. */
        fun notificationId(captureId: Long) = 1_000_000 + captureId.toInt()
    }
}

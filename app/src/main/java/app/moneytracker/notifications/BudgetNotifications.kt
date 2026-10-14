package app.moneytracker.notifications

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
import app.moneytracker.domain.model.BudgetLevel
import app.moneytracker.domain.model.BudgetProgress
import app.moneytracker.domain.usecase.BudgetNotifier
import app.moneytracker.ui.MainActivity
import app.moneytracker.data.db.DefaultNames
import app.moneytracker.ui.format.formatMoney
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class BudgetNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val defaultNames: DefaultNames,
) : BudgetNotifier {

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_budgets),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notification_channel_budgets_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun notify(progress: BudgetProgress, level: BudgetLevel) {
        // Без разрешения (Android 13+) молча пропускаем: статус всё равно виден на вкладке «Бюджеты».
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val locale = context.resources.configuration.locales[0]
        val name = progress.category?.let { defaultNames.resolve(it.defaultKey, it.name) } ?: "—"
        val title = context.getString(
            if (level == BudgetLevel.EXCEEDED) R.string.notification_budget_exceeded else R.string.notification_budget_warning,
            name,
        )
        val text = context.getString(
            R.string.notification_budget_text,
            formatMoney(progress.spent, locale),
            formatMoney(progress.limit, locale),
        )
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        // id = бюджет: новое уведомление по той же категории заменяет старое, а не копится.
        NotificationManagerCompat.from(context).notify(progress.budget.id.toInt(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "budgets"
    }
}

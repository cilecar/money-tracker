package app.moneytracker.ui.data

import android.content.Context
import android.content.Intent
import app.moneytracker.ui.MainActivity
import kotlin.system.exitProcess

/**
 * Полный перезапуск процесса после подмены файла базы: синглтоны Hilt (Room, репозитории)
 * держат закрытый экземпляр БД, аккуратно пересоздать их все проще через новый процесс.
 */
object AppRestarter {
    fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        exitProcess(0)
    }
}

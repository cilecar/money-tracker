package app.moneytracker.capture

import android.content.Context
import app.moneytracker.domain.capture.RawNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DebugNotification(
    val packageName: String,
    val key: String,
    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val postedAt: String,
) {
    /** Текст для копирования: все поля, как их видит парсер. */
    fun describe(): String = buildString {
        appendLine("package: $packageName")
        appendLine("postedAt: $postedAt")
        appendLine("title: $title")
        appendLine("text: $text")
        appendLine("bigText: $bigText")
        append("subText: $subText")
    }
}

/**
 * Режим отладки автозахвата: последние [LIMIT] сырых уведомлений из белого списка.
 *
 * Отдельный файл в noBackupFilesDir, а не таблица в основной базе: в манифесте allowBackup=true,
 * и база может уехать в облачный бэкап Google. Тексты банковских уведомлений устройство не покидают —
 * ни в бэкап, ни в JSON-выгрузку, ни в сеть.
 */
@Singleton
class NotificationDebugStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.noBackupFilesDir, "capture-debug.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<DebugNotification>?>(null)

    /** Новые сверху; null — ещё не прочитано с диска. */
    val items: StateFlow<List<DebugNotification>?> = _items.asStateFlow()

    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun add(n: RawNotification) = mutex.withLock {
        val entry = DebugNotification(n.packageName, n.key, n.title, n.text, n.bigText, n.subText, n.postedAt.toString())
        write((listOf(entry) + ensureLoaded()).take(LIMIT))
    }

    suspend fun clear() = mutex.withLock { write(emptyList()) }

    private suspend fun ensureLoaded(): List<DebugNotification> = _items.value ?: withContext(Dispatchers.IO) {
        // Битый файл (оборванная запись) не должен ронять сервис — начинаем заново.
        runCatching { if (file.exists()) json.decodeFromString<List<DebugNotification>>(file.readText()) else emptyList() }
            .getOrDefault(emptyList())
    }.also { _items.value = it }

    private suspend fun write(list: List<DebugNotification>) {
        withContext(Dispatchers.IO) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(list))
            tmp.renameTo(file)
        }
        _items.value = list
    }

    companion object {
        const val LIMIT = 50

        fun DebugNotification.postedInstant(): Instant = Instant.parse(postedAt)
    }
}

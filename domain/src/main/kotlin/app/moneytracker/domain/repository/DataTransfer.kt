package app.moneytracker.domain.repository

import java.io.InputStream
import java.io.OutputStream

enum class CsvErrorReason { BAD_DATE, BAD_AMOUNT, BAD_CURRENCY, MISSING_CATEGORY, BAD_RATE }

data class CsvRowError(
    /** Номер строки в файле (1 — заголовок), чтобы пользователь мог найти её в редакторе. */
    val line: Int,
    val reason: CsvErrorReason,
)

data class CsvImportResult(
    val imported: Int,
    /** Строки, уже существующие в базе (дата+время, сумма, валюта, категория совпадают). */
    val duplicates: Int,
    val createdCategories: Int,
    val createdAccounts: Int,
    val errors: List<CsvRowError>,
)

data class JsonImportResult(val expenses: Int, val categories: Int, val accounts: Int, val budgets: Int, val recurring: Int)

/** Файл не того формата, повреждён или из более новой версии приложения. */
class BackupFormatException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NOT_A_BACKUP, NEWER_VERSION, INCONSISTENT, NO_HEADER }
}

/**
 * Импорт/экспорт. Потоки — java.io, без Android: UI открывает их через SAF
 * (ContentResolver), а реализация не знает, файл это, Google Drive или флешка.
 */
interface DataTransfer {
    /** Расходы в CSV — для таблиц (Excel, Google Sheets). */
    suspend fun exportCsv(out: OutputStream): Int

    /** Добавляет расходы из CSV к существующим; недостающие категории и способы оплаты создаются. */
    suspend fun importCsv(input: InputStream): CsvImportResult

    /** Полная выгрузка всех данных и настроек. */
    suspend fun exportJson(out: OutputStream)

    /** Заменяет все данные содержимым файла. При любой ошибке текущие данные не трогаются. */
    suspend fun importJson(input: InputStream): JsonImportResult

    /** Побайтовая копия файла базы. */
    suspend fun backupDatabase(out: OutputStream)

    /**
     * Проверяет копию и подменяет ею файл базы. После успеха приложение нужно
     * перезапустить: открытый экземпляр Room работает со старым файлом.
     */
    suspend fun restoreDatabase(input: InputStream)
}

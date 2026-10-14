package app.moneytracker.data.io

import java.io.Reader
import java.io.Writer

/**
 * Минимальный CSV по RFC 4180: поля в кавычках, удвоенные кавычки внутри,
 * переводы строк внутри поля. Отдельная библиотека ради этого не нужна.
 */
object Csv {
    /** BOM в начале файла: без него Excel открывает UTF-8 как cp1251 и кириллица ломается. */
    const val BOM = '\uFEFF'

    fun writeRow(writer: Writer, fields: List<String?>, delimiter: Char = ',') {
        fields.forEachIndexed { i, field ->
            if (i > 0) writer.write(delimiter.code)
            writer.write(quote(field.orEmpty(), delimiter))
        }
        writer.write("\r\n")
    }

    private fun quote(value: String, delimiter: Char): String {
        val needsQuotes = value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } ||
            value.startsWith(' ') || value.endsWith(' ')
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    /** Строка таблицы с номером строки файла, где она начинается (для сообщений об ошибках). */
    data class Row(val line: Int, val fields: List<String>)

    /**
     * Читает весь CSV. Разделитель определяется по заголовку: русский Excel
     * сохраняет CSV через «;», остальные — через «,».
     */
    fun read(reader: Reader): List<Row> {
        val text = reader.readText().removePrefix(BOM.toString())
        val firstLine = text.substringBefore('\n')
        val delimiter = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','

        val rows = mutableListOf<Row>()
        val field = StringBuilder()
        var fields = mutableListOf<String>()
        var inQuotes = false
        var line = 1
        var rowStart = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' && (inQuotes || field.isEmpty()) -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> {
                    fields += field.toString()
                    field.clear()
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    fields += field.toString()
                    field.clear()
                    if (fields.any { it.isNotEmpty() }) rows += Row(rowStart, fields)
                    fields = mutableListOf()
                    line++
                    rowStart = line
                }
                else -> {
                    if (c == '\n') line++
                    field.append(c)
                }
            }
            i++
        }
        fields += field.toString()
        if (fields.any { it.isNotEmpty() }) rows += Row(rowStart, fields)
        return rows
    }
}

package app.moneytracker.data.io

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class CsvTest {

    private fun write(vararg rows: List<String?>, delimiter: Char = ','): String =
        StringWriter().also { w -> rows.forEach { Csv.writeRow(w, it, delimiter) } }.toString()

    @Test
    fun `quotes only when needed`() {
        assertEquals("a,b c,\"x,y\",\"say \"\"hi\"\"\",\r\n", write(listOf("a", "b c", "x,y", "say \"hi\"", null)))
        assertEquals("\" padded \"\r\n", write(listOf(" padded ")))
    }

    @Test
    fun `round trip with tricky fields`() {
        val rows = listOf(
            listOf("date", "comment", "tags"),
            listOf("2026-10-14 13:45", "Кофе, \"латте\"\nс собой", "кафе;работа"),
            listOf("2026-10-15 09:00", "", ""),
        )
        val parsed = Csv.read(StringReader(write(*rows.toTypedArray())))
        assertEquals(rows, parsed.map { it.fields })
        assertEquals(listOf(1, 2, 4), parsed.map { it.line }) // многострочное поле сдвигает номер следующей строки
    }

    @Test
    fun `semicolon delimiter and bom are detected`() {
        val text = Csv.BOM + "date;amount;comment\r\n14.10.2026;1 250,50;\"a;b\"\r\n"
        val parsed = Csv.read(StringReader(text))
        assertEquals(listOf("date", "amount", "comment"), parsed[0].fields)
        assertEquals(listOf("14.10.2026", "1 250,50", "a;b"), parsed[1].fields)
    }

    @Test
    fun `blank lines are skipped and missing trailing newline is fine`() {
        val parsed = Csv.read(StringReader("a,b\n\n1,2"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), parsed.map { it.fields })
        assertEquals(3, parsed[1].line)
    }
}

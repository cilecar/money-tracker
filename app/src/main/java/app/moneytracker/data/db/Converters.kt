package app.moneytracker.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * - LocalDate → epochDay (INTEGER): сортируется и сравнивается в SQL как число.
 * - Instant → epoch millis (INTEGER).
 * - BigDecimal → TEXT: SQLite REAL — это double, курс потерял бы точность.
 */
class Converters {
    @TypeConverter fun localDateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToLocalDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun bigDecimalToString(value: BigDecimal?): String? = value?.toPlainString()
    @TypeConverter fun stringToBigDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)

    @TypeConverter fun stringListToJson(value: List<String>?): String? = value?.let { Json.encodeToString(it) }
    @TypeConverter fun jsonToStringList(value: String?): List<String>? = value?.let { Json.decodeFromString(it) }
}

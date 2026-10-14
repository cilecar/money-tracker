package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.RateSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * source входит в ключ: ручной курс и курс из API на одну дату живут рядом,
 * и синхронизация не затирает ручной ввод.
 */
@Entity(tableName = "exchange_rate", primaryKeys = ["date", "currency", "source"])
data class ExchangeRateEntity(
    val date: LocalDate,
    val currency: CurrencyCode,
    val source: RateSource,
    @ColumnInfo(name = "per_eur") val perEur: BigDecimal,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Instant,
)

package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.moneytracker.data.db.entity.ExchangeRateEntity
import app.moneytracker.domain.model.CurrencyCode
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ExchangeRateDao {
    /**
     * Все строки за последнюю дату по каждой валюте (обычно 1–3 строки на валюту:
     * API, запасной API, ручной). Какую из них считать действующей, решает домен.
     */
    @Query(
        """
        SELECT * FROM exchange_rate AS r
        WHERE r.date = (SELECT MAX(date) FROM exchange_rate WHERE currency = r.currency)
        """,
    )
    fun observeLatest(): Flow<List<ExchangeRateEntity>>

    @Query(
        """
        SELECT * FROM exchange_rate AS r
        WHERE r.date = (SELECT MAX(date) FROM exchange_rate WHERE currency = r.currency)
        """,
    )
    suspend fun latest(): List<ExchangeRateEntity>

    @Query("SELECT MAX(fetched_at) FROM exchange_rate WHERE source != 'MANUAL'")
    fun observeLastSync(): Flow<Instant?>

    @Query("SELECT MAX(fetched_at) FROM exchange_rate WHERE source != 'MANUAL'")
    suspend fun lastSync(): Instant?

    @Upsert
    suspend fun upsert(rates: List<ExchangeRateEntity>)

    @Query("DELETE FROM exchange_rate WHERE currency = :currency AND source = 'MANUAL'")
    suspend fun clearManual(currency: CurrencyCode)
}

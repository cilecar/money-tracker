package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.PaymentMethodType

@Entity(tableName = "payment_method")
data class PaymentMethodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PaymentMethodType,
    val currency: CurrencyCode?,
    @ColumnInfo(name = "default_key") val defaultKey: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean,
)

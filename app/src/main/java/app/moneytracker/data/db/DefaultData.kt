package app.moneytracker.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.StringRes
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.moneytracker.R
import app.moneytracker.domain.model.PaymentMethodType

object DefaultData {

    data class DefaultCategory(val key: String, @StringRes val nameRes: Int, val iconKey: String, val color: Int)
    data class DefaultPaymentMethod(val key: String, @StringRes val nameRes: Int, val type: PaymentMethodType)

    val categories = listOf(
        DefaultCategory("food", R.string.category_food, "restaurant", 0xFFE57373.toInt()),
        DefaultCategory("groceries", R.string.category_groceries, "shopping_cart", 0xFF81C784.toInt()),
        DefaultCategory("transport", R.string.category_transport, "directions_bus", 0xFF64B5F6.toInt()),
        DefaultCategory("housing", R.string.category_housing, "home", 0xFFFFB74D.toInt()),
        DefaultCategory("communication", R.string.category_communication, "smartphone", 0xFF4DD0E1.toInt()),
        DefaultCategory("entertainment", R.string.category_entertainment, "theaters", 0xFFBA68C8.toInt()),
        DefaultCategory("health", R.string.category_health, "medical_services", 0xFFF06292.toInt()),
        DefaultCategory("clothes", R.string.category_clothes, "checkroom", 0xFFA1887F.toInt()),
        DefaultCategory("subscriptions", R.string.category_subscriptions, "subscriptions", 0xFF7986CB.toInt()),
        DefaultCategory("other", R.string.category_other, "more_horiz", 0xFF90A4AE.toInt()),
    )

    val paymentMethods = listOf(
        DefaultPaymentMethod("cash", R.string.payment_cash, PaymentMethodType.CASH),
    )

    /** Справочник ключ → строковый ресурс, чтобы UI показывал название в текущей локали. */
    @StringRes
    fun nameResFor(defaultKey: String): Int? =
        categories.firstOrNull { it.key == defaultKey }?.nameRes
            ?: paymentMethods.firstOrNull { it.key == defaultKey }?.nameRes
}

/**
 * Заполняет справочники при создании БД. Сырые ContentValues вместо DAO: в onCreate
 * экземпляр AppDatabase ещё не готов, а повторно открывать его изнутри колбэка нельзя.
 */
class DatabaseSeeder(private val context: Context) : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        DefaultData.categories.forEachIndexed { index, c ->
            db.insert(
                "category",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("name", context.getString(c.nameRes))
                    put("default_key", c.key)
                    put("icon_key", c.iconKey)
                    put("color", c.color)
                    putNull("parent_id")
                    put("sort_order", index)
                    put("archived", 0)
                },
            )
        }
        DefaultData.paymentMethods.forEachIndexed { index, p ->
            db.insert(
                "payment_method",
                SQLiteDatabase.CONFLICT_ABORT,
                ContentValues().apply {
                    put("name", context.getString(p.nameRes))
                    put("type", p.type.name)
                    putNull("currency")
                    put("default_key", p.key)
                    put("sort_order", index)
                    put("archived", 0)
                },
            )
        }
    }
}

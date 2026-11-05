package app.moneytracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.moneytracker.domain.receipt.ItemUnit
import app.moneytracker.domain.receipt.ReceiptStatus
import java.time.Instant

/** Сеть — юрлицо по ИНН. Короткое имя и категорию задаёт пользователь, загрузка их не трогает. */
@Entity(
    tableName = "chain",
    foreignKeys = [ForeignKey(CategoryEntity::class, ["id"], ["default_category_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("tax_id", unique = true), Index("default_category_id")],
)
data class ChainEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tax_id") val taxId: String,
    @ColumnInfo(name = "official_name") val officialName: String,
    @ColumnInfo(name = "short_name") val shortName: String?,
    @ColumnInfo(name = "default_category_id") val defaultCategoryId: Long?,
)

@Entity(
    tableName = "store",
    foreignKeys = [ForeignKey(ChainEntity::class, ["id"], ["chain_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chain_id", "location_code", unique = true)],
)
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chain_id") val chainId: Long,
    @ColumnInfo(name = "location_code") val locationCode: String,
    @ColumnInfo(name = "location_name") val locationName: String,
    val address: String,
    val city: String,
)

/** Чек. Удаление расхода открепляет чек (SET NULL): история цен остаётся. */
@Entity(
    tableName = "receipt",
    foreignKeys = [
        ForeignKey(StoreEntity::class, ["id"], ["store_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(ExpenseEntity::class, ["id"], ["expense_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("invoice_number", unique = true),
        Index("expense_id", unique = true),
        Index("store_id"),
        Index("status"),
    ],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "invoice_number") val invoiceNumber: String,
    val url: String,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "issued_at") val issuedAt: Instant,
    @ColumnInfo(name = "store_id") val storeId: Long?,
    @ColumnInfo(name = "expense_id") val expenseId: Long?,
    val status: ReceiptStatus,
    val error: String?,
    val journal: String?,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

@Entity(tableName = "product")
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val unit: ItemUnit,
)

/** Товар в сети. Продукт удаляется только пустым (RESTRICT), поэтому у артикула он есть всегда. */
@Entity(
    tableName = "article",
    foreignKeys = [
        ForeignKey(ChainEntity::class, ["id"], ["chain_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ProductEntity::class, ["id"], ["product_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("chain_id", "code", unique = true), Index("product_id")],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "chain_id") val chainId: Long,
    val code: String,
    val name: String,
    val unit: ItemUnit,
    @ColumnInfo(name = "product_id") val productId: Long,
)

/** Позиция чека; article_id NULL — скидка. */
@Entity(
    tableName = "receipt_item",
    foreignKeys = [
        ForeignKey(ReceiptEntity::class, ["id"], ["receipt_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ArticleEntity::class, ["id"], ["article_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("receipt_id"), Index("article_id")],
)
data class ReceiptItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    val position: Int,
    @ColumnInfo(name = "raw_name") val rawName: String,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_price_minor") val unitPriceMinor: Long,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "vat_rate") val vatRate: Int?,
    @ColumnInfo(name = "article_id") val articleId: Long?,
)

/** Строка истории цен: позиция с артикулом из загруженного чека. */
data class PricePointRow(
    @ColumnInfo(name = "product_id") val productId: Long,
    @ColumnInfo(name = "article_id") val articleId: Long,
    @ColumnInfo(name = "chain_id") val chainId: Long,
    @ColumnInfo(name = "store_id") val storeId: Long,
    @ColumnInfo(name = "receipt_id") val receiptId: Long,
    @ColumnInfo(name = "expense_id") val expenseId: Long?,
    @ColumnInfo(name = "issued_at") val issuedAt: Instant,
    @ColumnInfo(name = "quantity_milli") val quantityMilli: Long,
    @ColumnInfo(name = "unit_price_minor") val unitPriceMinor: Long,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "item_id") val itemId: Long,
)

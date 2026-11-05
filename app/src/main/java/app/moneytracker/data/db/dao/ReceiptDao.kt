package app.moneytracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.moneytracker.data.db.entity.ArticleEntity
import app.moneytracker.data.db.entity.ChainEntity
import app.moneytracker.data.db.entity.PricePointRow
import app.moneytracker.data.db.entity.ProductEntity
import app.moneytracker.data.db.entity.ReceiptEntity
import app.moneytracker.data.db.entity.ReceiptItemEntity
import app.moneytracker.data.db.entity.StoreEntity
import app.moneytracker.domain.receipt.ReceiptStatus
import kotlinx.coroutines.flow.Flow

data class StoreReceiptCount(
    @androidx.room.ColumnInfo(name = "store_id") val storeId: Long,
    val count: Int,
)

@Dao
interface ReceiptDao {
    // --- Чеки ---

    @Query("SELECT * FROM receipt WHERE id = :id") suspend fun get(id: Long): ReceiptEntity?
    @Query("SELECT * FROM receipt WHERE id = :id") fun observe(id: Long): Flow<ReceiptEntity?>
    @Query("SELECT * FROM receipt WHERE invoice_number = :number") suspend fun findByInvoice(number: String): ReceiptEntity?
    @Query("SELECT * FROM receipt WHERE expense_id = :expenseId") suspend fun getByExpense(expenseId: Long): ReceiptEntity?
    @Query("SELECT * FROM receipt WHERE expense_id = :expenseId") fun observeByExpense(expenseId: Long): Flow<ReceiptEntity?>
    @Query("SELECT expense_id FROM receipt WHERE expense_id IN (:ids)") suspend fun expensesWithReceipt(ids: List<Long>): List<Long>
    @Query("SELECT * FROM receipt ORDER BY issued_at DESC") fun observeAll(): Flow<List<ReceiptEntity>>
    @Query("SELECT id FROM receipt WHERE status = 'PENDING' ORDER BY id") suspend fun pendingIds(): List<Long>

    /** -1 — чек с таким номером уже есть. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(receipt: ReceiptEntity): Long

    @Query(
        "UPDATE receipt SET status = 'LOADED', error = NULL, store_id = :storeId, total_minor = :totalMinor, " +
            "journal = :journal WHERE id = :id",
    )
    suspend fun markLoaded(id: Long, storeId: Long, totalMinor: Long, journal: String?)

    @Query("UPDATE receipt SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: ReceiptStatus, error: String?)

    @Query("UPDATE receipt SET expense_id = :expenseId WHERE id = :id") suspend fun setExpense(id: Long, expenseId: Long?)
    @Query("DELETE FROM receipt WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT store_id, count(*) AS count FROM receipt WHERE store_id IS NOT NULL GROUP BY store_id")
    fun observeCountsByStore(): Flow<List<StoreReceiptCount>>

    // --- Сети и точки ---

    @Query("SELECT * FROM chain WHERE id = :id") suspend fun getChain(id: Long): ChainEntity?
    @Query("SELECT * FROM chain WHERE tax_id = :taxId") suspend fun findChain(taxId: String): ChainEntity?
    @Insert suspend fun insertChain(chain: ChainEntity): Long
    @Query("UPDATE chain SET official_name = :name WHERE id = :id") suspend fun setChainOfficialName(id: Long, name: String)
    @Query("UPDATE chain SET short_name = :name WHERE id = :id") suspend fun setChainShortName(id: Long, name: String?)
    @Query("UPDATE chain SET default_category_id = :categoryId WHERE id = :id") suspend fun setChainDefaultCategory(id: Long, categoryId: Long?)
    @Query("SELECT * FROM chain ORDER BY coalesce(short_name, official_name)") fun observeChains(): Flow<List<ChainEntity>>

    @Query("SELECT * FROM store WHERE id = :id") suspend fun getStore(id: Long): StoreEntity?
    @Query("SELECT * FROM store WHERE chain_id = :chainId AND location_code = :code") suspend fun findStore(chainId: Long, code: String): StoreEntity?
    @Insert suspend fun insertStore(store: StoreEntity): Long
    @Query("UPDATE store SET location_name = :name, address = :address, city = :city WHERE id = :id")
    suspend fun updateStore(id: Long, name: String, address: String, city: String)
    @Query("SELECT * FROM store ORDER BY location_name") fun observeStores(): Flow<List<StoreEntity>>

    // --- Позиции ---

    @Query("SELECT * FROM receipt_item WHERE receipt_id = :receiptId ORDER BY position") fun observeItems(receiptId: Long): Flow<List<ReceiptItemEntity>>
    @Query("DELETE FROM receipt_item WHERE receipt_id = :receiptId") suspend fun deleteItems(receiptId: Long)
    @Insert suspend fun insertItems(items: List<ReceiptItemEntity>)

    // --- Артикулы и продукты ---

    @Query("SELECT * FROM article WHERE id = :id") suspend fun getArticle(id: Long): ArticleEntity?
    @Query("SELECT * FROM article WHERE chain_id = :chainId AND code = :code") suspend fun findArticle(chainId: Long, code: String): ArticleEntity?
    @Insert suspend fun insertArticle(article: ArticleEntity): Long
    @Query("SELECT * FROM article ORDER BY name") fun observeArticles(): Flow<List<ArticleEntity>>
    @Query("SELECT * FROM article WHERE id IN (SELECT article_id FROM receipt_item WHERE receipt_id = :receiptId)")
    fun observeArticlesOfReceipt(receiptId: Long): Flow<List<ArticleEntity>>
    @Query("SELECT count(*) FROM article WHERE product_id = :productId") suspend fun articleCount(productId: Long): Int
    @Query("UPDATE article SET product_id = :to WHERE product_id = :from") suspend fun moveArticles(from: Long, to: Long)
    @Query("UPDATE article SET product_id = :productId WHERE id = :articleId") suspend fun setArticleProduct(articleId: Long, productId: Long)

    @Query("SELECT * FROM product WHERE id = :id") suspend fun getProduct(id: Long): ProductEntity?
    @Query("SELECT * FROM product WHERE id = :id") fun observeProduct(id: Long): Flow<ProductEntity?>
    @Query("SELECT * FROM product ORDER BY name") fun observeProducts(): Flow<List<ProductEntity>>
    @Insert suspend fun insertProduct(product: ProductEntity): Long
    @Query("UPDATE product SET name = :name WHERE id = :id") suspend fun renameProduct(id: Long, name: String)
    @Query("DELETE FROM product WHERE id = :id") suspend fun deleteProduct(id: Long)

    @Query("DELETE FROM article WHERE id NOT IN (SELECT article_id FROM receipt_item WHERE article_id IS NOT NULL)")
    suspend fun deleteOrphanArticles()

    @Query("DELETE FROM product WHERE id NOT IN (SELECT product_id FROM article)")
    suspend fun deleteOrphanProducts()

    // --- История цен ---

    @Query(
        "SELECT a.product_id, i.article_id, a.chain_id, r.store_id, r.id AS receipt_id, r.expense_id, r.issued_at, " +
            "i.quantity_milli, i.unit_price_minor, i.total_minor, i.id AS item_id " +
            "FROM receipt_item i JOIN article a ON a.id = i.article_id JOIN receipt r ON r.id = i.receipt_id " +
            "WHERE r.status = 'LOADED' AND r.store_id IS NOT NULL ORDER BY r.issued_at",
    )
    fun observePricePoints(): Flow<List<PricePointRow>>

    @Query(
        "SELECT a.product_id, i.article_id, a.chain_id, r.store_id, r.id AS receipt_id, r.expense_id, r.issued_at, " +
            "i.quantity_milli, i.unit_price_minor, i.total_minor, i.id AS item_id " +
            "FROM receipt_item i JOIN article a ON a.id = i.article_id JOIN receipt r ON r.id = i.receipt_id " +
            "WHERE r.status = 'LOADED' AND r.store_id IS NOT NULL AND a.product_id = :productId ORDER BY r.issued_at",
    )
    fun observePricePoints(productId: Long): Flow<List<PricePointRow>>
}

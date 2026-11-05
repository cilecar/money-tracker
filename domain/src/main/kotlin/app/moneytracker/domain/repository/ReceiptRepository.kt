package app.moneytracker.domain.repository

import app.moneytracker.domain.receipt.ArticleWithChain
import app.moneytracker.domain.receipt.Chain
import app.moneytracker.domain.receipt.ChainWithStores
import app.moneytracker.domain.receipt.FetchedReceipt
import app.moneytracker.domain.receipt.FiscalLink
import app.moneytracker.domain.receipt.ItemToSave
import app.moneytracker.domain.receipt.PricePoint
import app.moneytracker.domain.receipt.Product
import app.moneytracker.domain.receipt.Receipt
import app.moneytracker.domain.receipt.ReceiptDetails
import app.moneytracker.domain.receipt.ReceiptRow
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface ReceiptRepository {
    suspend fun get(id: Long): Receipt?
    suspend fun findByInvoice(invoiceNumber: String): Receipt?
    suspend fun getByExpense(expenseId: Long): Receipt?
    fun observeByExpense(expenseId: Long): Flow<Receipt?>

    /** Какие из [expenseIds] уже с чеком. */
    suspend fun expensesWithReceipt(expenseIds: Collection<Long>): Set<Long>

    /** INSERT OR IGNORE по номеру чека. @return id или null, если чек с таким номером уже есть. */
    suspend fun insertPending(link: FiscalLink, now: Instant): Long?

    suspend fun getPendingIds(): List<Long>

    /**
     * Одной транзакцией: сеть и точка (создаются или обновляются), артикулы (новый — со своим продуктом),
     * позиции чека ЗАМЕНЯЮТСЯ, сумма из шапки, статус LOADED.
     */
    suspend fun saveLoaded(receiptId: Long, fetched: FetchedReceipt, items: List<ItemToSave>)

    suspend fun markFailed(receiptId: Long, reason: String)
    suspend fun markPending(receiptId: Long)

    /** @return false — чека нет или у расхода уже другой чек. */
    suspend fun attach(receiptId: Long, expenseId: Long): Boolean

    suspend fun detach(receiptId: Long)

    /** Удаляет чек, затем артикулы без позиций, затем продукты без артикулов. */
    suspend fun delete(receiptId: Long)

    fun observeDetails(receiptId: Long): Flow<ReceiptDetails?>
    fun observeRows(): Flow<List<ReceiptRow>>
    fun observeChains(): Flow<List<ChainWithStores>>
    suspend fun chainOfReceipt(receiptId: Long): Chain?
    suspend fun setChainShortName(chainId: Long, name: String?)
    suspend fun setChainDefaultCategory(chainId: Long, categoryId: Long?)

    fun observeProducts(): Flow<List<Product>>
    fun observeProduct(id: Long): Flow<Product?>
    fun observeArticles(productId: Long): Flow<List<ArticleWithChain>>
    fun observeAllArticles(): Flow<List<ArticleWithChain>>

    /** Покупки без скидок, только из загруженных чеков. */
    fun observePricePoints(): Flow<List<PricePoint>>
    fun observePricePoints(productId: Long): Flow<List<PricePoint>>

    suspend fun renameProduct(id: Long, name: String)

    /** Артикулы source → target, source удаляется. @return false — разные единицы или один и тот же продукт. */
    suspend fun mergeProducts(targetId: Long, sourceId: Long): Boolean

    /** Новый продукт для артикула. @return его id или null, если артикул в продукте единственный. */
    suspend fun detachArticle(articleId: Long): Long?
}

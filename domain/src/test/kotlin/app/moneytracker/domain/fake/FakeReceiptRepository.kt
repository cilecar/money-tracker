package app.moneytracker.domain.fake

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
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.domain.receipt.ReceiptSyncScheduler
import app.moneytracker.domain.repository.ReceiptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Чеки в памяти: только то, что нужно сценариям; хранение сетей и позиций проверяется на настоящей базе. */
class FakeReceiptRepository : ReceiptRepository {
    val receipts = MutableStateFlow<Map<Long, Receipt>>(emptyMap())
    /** Сеть чека после saveLoaded — по taxId. */
    val chains = mutableMapOf<Long, Chain>()
    private val chainOfReceipt = mutableMapOf<Long, Long>()
    val saved = mutableListOf<Pair<Long, List<ItemToSave>>>()
    private var nextId = 1L

    override suspend fun get(id: Long) = receipts.value[id]
    override suspend fun findByInvoice(invoiceNumber: String) = receipts.value.values.firstOrNull { it.invoiceNumber == invoiceNumber }
    override suspend fun getByExpense(expenseId: Long) = receipts.value.values.firstOrNull { it.expenseId == expenseId }
    override fun observeByExpense(expenseId: Long): Flow<Receipt?> = receipts.map { m -> m.values.firstOrNull { it.expenseId == expenseId } }
    override suspend fun expensesWithReceipt(expenseIds: Collection<Long>) =
        receipts.value.values.mapNotNull { it.expenseId }.filter { it in expenseIds }.toSet()

    override suspend fun insertPending(link: FiscalLink, now: Instant): Long? {
        if (findByInvoice(link.invoiceNumber) != null) return null
        val id = nextId++
        receipts.value += id to Receipt(id, link.invoiceNumber, link.url, link.totalMinor, link.issuedAt, null, null, ReceiptStatus.PENDING, null, null, now)
        return id
    }

    override suspend fun getPendingIds() = receipts.value.values.filter { it.status == ReceiptStatus.PENDING }.map { it.id }

    override suspend fun saveLoaded(receiptId: Long, fetched: FetchedReceipt, items: List<ItemToSave>) {
        saved += receiptId to items
        val chain = chains.values.firstOrNull { it.taxId == fetched.taxId }
            ?: Chain(chains.size + 1L, fetched.taxId, fetched.officialName, null, null).also { chains[it.id] = it }
        chainOfReceipt[receiptId] = chain.id
        update(receiptId) { it.copy(status = ReceiptStatus.LOADED, storeId = 1, totalMinor = fetched.totalMinor, journal = fetched.journal, error = null) }
    }

    override suspend fun markFailed(receiptId: Long, reason: String) = update(receiptId) { it.copy(status = ReceiptStatus.FAILED, error = reason) }
    override suspend fun markPending(receiptId: Long) = update(receiptId) { it.copy(status = ReceiptStatus.PENDING, error = null) }

    override suspend fun attach(receiptId: Long, expenseId: Long): Boolean {
        if (receipts.value[receiptId] == null || receipts.value.values.any { it.expenseId == expenseId && it.id != receiptId }) return false
        update(receiptId) { it.copy(expenseId = expenseId) }
        return true
    }

    override suspend fun detach(receiptId: Long) = update(receiptId) { it.copy(expenseId = null) }
    override suspend fun delete(receiptId: Long) { receipts.value -= receiptId }
    override suspend fun chainOfReceipt(receiptId: Long): Chain? = chainOfReceipt[receiptId]?.let { chains[it] }
    override suspend fun setChainShortName(chainId: Long, name: String?) { chains[chainId] = chains.getValue(chainId).copy(shortName = name) }
    override suspend fun setChainDefaultCategory(chainId: Long, categoryId: Long?) {
        chains[chainId] = chains.getValue(chainId).copy(defaultCategoryId = categoryId)
    }

    private fun update(id: Long, f: (Receipt) -> Receipt) {
        receipts.value[id]?.let { receipts.value += id to f(it) }
    }

    override fun observeDetails(receiptId: Long): Flow<ReceiptDetails?> = TODO()
    override fun observeRows(): Flow<List<ReceiptRow>> = TODO()
    override fun observeChains(): Flow<List<ChainWithStores>> = TODO()
    override fun observeProducts(): Flow<List<Product>> = TODO()
    override fun observeProduct(id: Long): Flow<Product?> = TODO()
    override fun observeArticles(productId: Long): Flow<List<ArticleWithChain>> = TODO()
    override fun observeAllArticles(): Flow<List<ArticleWithChain>> = TODO()
    override fun observePricePoints(): Flow<List<PricePoint>> = TODO()
    override fun observePricePoints(productId: Long): Flow<List<PricePoint>> = TODO()
    override suspend fun renameProduct(id: Long, name: String) = TODO()
    override suspend fun mergeProducts(targetId: Long, sourceId: Long): Boolean = TODO()
    override suspend fun detachArticle(articleId: Long): Long? = TODO()
}

class FakeReceiptSyncScheduler : ReceiptSyncScheduler {
    var calls = 0
    override fun syncReceiptsWhenOnline() { calls++ }
}

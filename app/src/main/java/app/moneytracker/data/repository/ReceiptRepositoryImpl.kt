package app.moneytracker.data.repository

import androidx.room.withTransaction
import app.moneytracker.data.db.AppDatabase
import app.moneytracker.data.db.dao.ReceiptDao
import app.moneytracker.data.db.entity.ArticleEntity
import app.moneytracker.data.db.entity.ChainEntity
import app.moneytracker.data.db.entity.PricePointRow
import app.moneytracker.data.db.entity.ProductEntity
import app.moneytracker.data.db.entity.ReceiptEntity
import app.moneytracker.data.db.entity.ReceiptItemEntity
import app.moneytracker.data.db.entity.StoreEntity
import app.moneytracker.domain.receipt.Article
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
import app.moneytracker.domain.receipt.ReceiptItem
import app.moneytracker.domain.receipt.ReceiptRow
import app.moneytracker.domain.receipt.ReceiptStatus
import app.moneytracker.domain.receipt.Store
import app.moneytracker.domain.repository.ReceiptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

class ReceiptRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: ReceiptDao,
    private val clock: Clock,
) : ReceiptRepository {

    override suspend fun get(id: Long): Receipt? = dao.get(id)?.toDomain()
    override suspend fun findByInvoice(invoiceNumber: String): Receipt? = dao.findByInvoice(invoiceNumber)?.toDomain()
    override suspend fun getByExpense(expenseId: Long): Receipt? = dao.getByExpense(expenseId)?.toDomain()
    override fun observeByExpense(expenseId: Long): Flow<Receipt?> = dao.observeByExpense(expenseId).map { it?.toDomain() }

    override suspend fun expensesWithReceipt(expenseIds: Collection<Long>): Set<Long> =
        expenseIds.chunked(500).flatMap { dao.expensesWithReceipt(it) }.toSet()

    override suspend fun insertPending(link: FiscalLink, now: Instant): Long? {
        val id = dao.insertIgnore(
            ReceiptEntity(
                invoiceNumber = link.invoiceNumber, url = link.url, totalMinor = link.totalMinor, issuedAt = link.issuedAt,
                storeId = null, expenseId = null, status = ReceiptStatus.PENDING, error = null, journal = null, createdAt = now,
            ),
        )
        return id.takeIf { it > 0 }
    }

    override suspend fun getPendingIds(): List<Long> = dao.pendingIds()

    override suspend fun saveLoaded(receiptId: Long, fetched: FetchedReceipt, items: List<ItemToSave>) = db.withTransaction {
        if (dao.get(receiptId) == null) return@withTransaction
        val chainId = dao.findChain(fetched.taxId)?.also { dao.setChainOfficialName(it.id, fetched.officialName) }?.id
            ?: dao.insertChain(ChainEntity(taxId = fetched.taxId, officialName = fetched.officialName, shortName = null, defaultCategoryId = null))
        val storeId = dao.findStore(chainId, fetched.locationCode)
            ?.also { dao.updateStore(it.id, fetched.locationName, fetched.address, fetched.city) }?.id
            ?: dao.insertStore(StoreEntity(chainId = chainId, locationCode = fetched.locationCode, locationName = fetched.locationName, address = fetched.address, city = fetched.city))

        dao.deleteItems(receiptId)
        dao.insertItems(
            items.mapIndexed { index, it ->
                val articleId = it.parsed?.let { p ->
                    dao.findArticle(chainId, p.code)?.id ?: run {
                        val productId = dao.insertProduct(ProductEntity(name = p.name, unit = p.unit))
                        dao.insertArticle(ArticleEntity(chainId = chainId, code = p.code, name = p.name, unit = p.unit, productId = productId))
                    }
                }
                ReceiptItemEntity(
                    receiptId = receiptId, position = index, rawName = it.item.rawName, quantityMilli = it.item.quantityMilli,
                    unitPriceMinor = it.item.unitPriceMinor, totalMinor = it.item.totalMinor, vatRate = it.item.vatRate, articleId = articleId,
                )
            },
        )
        dao.markLoaded(receiptId, storeId, fetched.totalMinor, fetched.journal)
        // Повторная загрузка могла оставить артикулы прежнего разбора без позиций.
        dao.deleteOrphanArticles()
        dao.deleteOrphanProducts()
    }

    override suspend fun markFailed(receiptId: Long, reason: String) = dao.setStatus(receiptId, ReceiptStatus.FAILED, reason)
    override suspend fun markPending(receiptId: Long) = dao.setStatus(receiptId, ReceiptStatus.PENDING, null)

    override suspend fun attach(receiptId: Long, expenseId: Long): Boolean = db.withTransaction {
        if (dao.get(receiptId) == null) return@withTransaction false
        val other = dao.getByExpense(expenseId)
        if (other != null && other.id != receiptId) return@withTransaction false
        dao.setExpense(receiptId, expenseId)
        true
    }

    override suspend fun detach(receiptId: Long) = dao.setExpense(receiptId, null)

    override suspend fun delete(receiptId: Long) = db.withTransaction {
        dao.delete(receiptId)
        dao.deleteOrphanArticles()
        dao.deleteOrphanProducts()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDetails(receiptId: Long): Flow<ReceiptDetails?> = dao.observe(receiptId).flatMapLatest { r ->
        if (r == null) return@flatMapLatest flowOf(null)
        combine(dao.observeItems(receiptId), dao.observeArticlesOfReceipt(receiptId)) { items, articles ->
            val store = r.storeId?.let { dao.getStore(it) }
            val chain = store?.let { dao.getChain(it.chainId) }
            ReceiptDetails(
                receipt = r.toDomain(),
                store = store?.toDomain(),
                chain = chain?.toDomain(),
                items = items.map { it.toDomain() },
                articles = articles.associate { it.id to it.toDomain() },
            )
        }
    }

    override fun observeRows(): Flow<List<ReceiptRow>> =
        combine(dao.observeAll(), dao.observeStores(), dao.observeChains()) { receipts, stores, chains ->
            val storeById = stores.associateBy { it.id }
            val chainById = chains.associateBy { it.id }
            receipts.map { r ->
                val store = r.storeId?.let(storeById::get)
                ReceiptRow(r.toDomain(), store?.let { chainById[it.chainId] }?.toDomain(), store?.toDomain())
            }
        }

    override fun observeChains(): Flow<List<ChainWithStores>> =
        combine(dao.observeChains(), dao.observeStores(), dao.observeCountsByStore()) { chains, stores, counts ->
            val byStore = counts.associate { it.storeId to it.count }
            chains.map { c ->
                val own = stores.filter { it.chainId == c.id }
                ChainWithStores(c.toDomain(), own.map { it.toDomain() }, own.sumOf { byStore[it.id] ?: 0 }, own.associate { it.id to (byStore[it.id] ?: 0) })
            }
        }

    override suspend fun chainOfReceipt(receiptId: Long): Chain? =
        dao.get(receiptId)?.storeId?.let { dao.getStore(it) }?.let { dao.getChain(it.chainId) }?.toDomain()

    override suspend fun setChainShortName(chainId: Long, name: String?) = dao.setChainShortName(chainId, name?.trim()?.ifEmpty { null })
    override suspend fun setChainDefaultCategory(chainId: Long, categoryId: Long?) = dao.setChainDefaultCategory(chainId, categoryId)

    override fun observeProducts(): Flow<List<Product>> = dao.observeProducts().map { list -> list.map { it.toDomain() } }
    override fun observeProduct(id: Long): Flow<Product?> = dao.observeProduct(id).map { it?.toDomain() }

    override fun observeArticles(productId: Long): Flow<List<ArticleWithChain>> =
        observeAllArticles().map { list -> list.filter { it.article.productId == productId } }

    override fun observeAllArticles(): Flow<List<ArticleWithChain>> =
        combine(dao.observeArticles(), dao.observeChains()) { articles, chains ->
            val byId = chains.associateBy { it.id }
            articles.mapNotNull { a -> byId[a.chainId]?.let { ArticleWithChain(a.toDomain(), it.toDomain()) } }
        }

    override fun observePricePoints(): Flow<List<PricePoint>> = dao.observePricePoints().map { rows -> rows.map { it.toDomain() } }
    override fun observePricePoints(productId: Long): Flow<List<PricePoint>> =
        dao.observePricePoints(productId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun renameProduct(id: Long, name: String) {
        name.trim().takeIf { it.isNotEmpty() }?.let { dao.renameProduct(id, it) }
    }

    override suspend fun mergeProducts(targetId: Long, sourceId: Long): Boolean = db.withTransaction {
        if (targetId == sourceId) return@withTransaction false
        val target = dao.getProduct(targetId) ?: return@withTransaction false
        val source = dao.getProduct(sourceId) ?: return@withTransaction false
        if (target.unit != source.unit) return@withTransaction false
        dao.moveArticles(sourceId, targetId)
        dao.deleteProduct(sourceId)
        true
    }

    override suspend fun detachArticle(articleId: Long): Long? = db.withTransaction {
        val article = dao.getArticle(articleId) ?: return@withTransaction null
        if (dao.articleCount(article.productId) <= 1) return@withTransaction null
        val productId = dao.insertProduct(ProductEntity(name = article.name, unit = article.unit))
        dao.setArticleProduct(articleId, productId)
        productId
    }

    private fun ReceiptEntity.toDomain() =
        Receipt(id, invoiceNumber, url, totalMinor, issuedAt, storeId, expenseId, status, error, journal, createdAt)

    private fun ChainEntity.toDomain() = Chain(id, taxId, officialName, shortName, defaultCategoryId)
    private fun StoreEntity.toDomain() = Store(id, chainId, locationCode, locationName, address, city)
    private fun ArticleEntity.toDomain() = Article(id, chainId, code, name, unit, productId)
    private fun ProductEntity.toDomain() = Product(id, name, unit)
    private fun ReceiptItemEntity.toDomain() =
        ReceiptItem(id, receiptId, position, rawName, quantityMilli, unitPriceMinor, totalMinor, vatRate, articleId)

    private fun PricePointRow.toDomain() = PricePoint(
        productId = productId, articleId = articleId, chainId = chainId, storeId = storeId, receiptId = receiptId,
        expenseId = expenseId, occurredAt = issuedAt, date = issuedAt.atZone(clock.zone).toLocalDate(),
        quantityMilli = quantityMilli, unitPriceMinor = unitPriceMinor, totalMinor = totalMinor, itemId = itemId,
    )
}

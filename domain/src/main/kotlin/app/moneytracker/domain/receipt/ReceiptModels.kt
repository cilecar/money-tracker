package app.moneytracker.domain.receipt

import java.time.Instant

/** То, что читается из QR сербского фискального чека без сети. Сумма — в парах, валюта всегда RSD. */
data class FiscalLink(val url: String, val invoiceNumber: String, val totalMinor: Long, val issuedAt: Instant)

sealed interface FiscalLinkResult {
    data class Ok(val link: FiscalLink) : FiscalLinkResult

    /** Не ссылка налоговой или битый vl. */
    data object NotFiscal : FiscalLinkResult

    /** Фискальный чек, но не обычная продажа (возврат, аванс, копия, обучение) или неизвестная версия. */
    data object Unsupported : FiscalLinkResult
}

/** Единица товара: цена за кг, за штуку или за литр. */
enum class ItemUnit { KG, PCS, L }

enum class ReceiptStatus { PENDING, LOADED, FAILED }

/** Сеть = юрлицо по ИНН (PIB). */
data class Chain(
    val id: Long,
    val taxId: String,
    val officialName: String,
    val shortName: String?,
    val defaultCategoryId: Long?,
) {
    val displayName: String get() = shortName ?: officialName
}

/** Конкретная точка сети. */
data class Store(
    val id: Long,
    val chainId: Long,
    val locationCode: String,
    val locationName: String,
    val address: String,
    val city: String,
)

data class Receipt(
    val id: Long,
    val invoiceNumber: String,
    val url: String,
    val totalMinor: Long,
    val issuedAt: Instant,
    /** null, пока чек не загружен. */
    val storeId: Long?,
    val expenseId: Long?,
    val status: ReceiptStatus,
    val error: String?,
    val journal: String?,
    val createdAt: Instant,
)

/** Позиция чека. Количество — в тысячных (0.534 кг → 534, 1 шт → 1000). */
data class ReceiptItem(
    val id: Long,
    val receiptId: Long,
    val position: Int,
    val rawName: String,
    val quantityMilli: Long,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val vatRate: Int?,
    /** null — скидка: хранится в чеке, но в историю цен не попадает. */
    val articleId: Long?,
) {
    val isDiscount: Boolean get() = articleId == null
}

/** Товар в сети: артикул Lidl или нормализованное название. */
data class Article(
    val id: Long,
    val chainId: Long,
    val code: String,
    val name: String,
    val unit: ItemUnit,
    val productId: Long,
)

/** Продукт для графиков — группа артикулов из разных сетей. */
data class Product(val id: Long, val name: String, val unit: ItemUnit)

data class FetchedItem(
    val rawName: String,
    val quantityMilli: Long,
    val unitPriceMinor: Long,
    val totalMinor: Long,
    val vatRate: Int?,
)

/** Чек, загруженный с сайта налоговой. */
data class FetchedReceipt(
    val taxId: String,
    val officialName: String,
    val locationCode: String,
    val locationName: String,
    val address: String,
    val city: String,
    val totalMinor: Long,
    val issuedAt: Instant,
    val journal: String?,
    val items: List<FetchedItem>,
)

data class ParsedItemName(val code: String, val name: String, val unit: ItemUnit)

/** [parsed] == null — скидка: хранится в чеке, но без артикула. */
data class ItemToSave(val item: FetchedItem, val parsed: ParsedItemName?)

sealed interface FetchResult {
    data class Ok(val receipt: FetchedReceipt) : FetchResult

    /** Сервер ответил, но чек не годится (не прошёл проверку, неизвестный формат). */
    data class Invalid(val reason: String) : FetchResult

    /** Нет сети, таймаут, ошибка сервера — повторить позже. */
    data object Unavailable : FetchResult
}

/** Загрузка чека по ссылке из QR. */
interface ReceiptSource {
    suspend fun fetch(url: String): FetchResult
}

/** Фоновая догрузка ожидающих чеков, когда появится сеть. */
interface ReceiptSyncScheduler {
    fun syncReceiptsWhenOnline()
}

/** Чек со всем, что нужно экрану чека. */
data class ReceiptDetails(val receipt: Receipt, val store: Store?, val chain: Chain?, val items: List<ReceiptItem>, val articles: Map<Long, Article>)

/** Строка списка «Чеки». */
data class ReceiptRow(val receipt: Receipt, val chain: Chain?, val store: Store?)

data class ChainWithStores(val chain: Chain, val stores: List<Store>, val receiptCount: Int, val receiptCountByStore: Map<Long, Int>)

data class ArticleWithChain(val article: Article, val chain: Chain)

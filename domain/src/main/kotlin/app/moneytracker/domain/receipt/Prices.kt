package app.moneytracker.domain.receipt

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** Одна покупка товара: цена за единицу (кг, шт, л) в парах. Скидки сюда не попадают. */
data class PricePoint(
    val productId: Long,
    val articleId: Long,
    val chainId: Long,
    val storeId: Long,
    val receiptId: Long,
    val expenseId: Long?,
    val occurredAt: Instant,
    val date: LocalDate,
    val quantityMilli: Long,
    val unitPriceMinor: Long,
    val totalMinor: Long,
)

data class StorePrices(val storeId: Long, val lastMinor: Long, val minMinor: Long, val maxMinor: Long, val lastDate: LocalDate)

data class ChainPrices(
    val chainId: Long,
    val lastMinor: Long,
    val minMinor: Long,
    val maxMinor: Long,
    val lastDate: LocalDate,
    val stores: List<StorePrices>,
)

data class ProductOverview(val product: Product, val last: PricePoint, val trendPercent: Int?)

object Prices {
    /** Сводка по сетям (внутри — по точкам); сначала те, где покупали последними. */
    fun byChain(points: List<PricePoint>): List<ChainPrices> = points.groupBy { it.chainId }
        .map { (chainId, chainPoints) ->
            val last = chainPoints.maxBy { it.occurredAt }
            val stores = chainPoints.groupBy { it.storeId }.map { (storeId, sp) ->
                val storeLast = sp.maxBy { it.occurredAt }
                StorePrices(storeId, storeLast.unitPriceMinor, sp.minOf { it.unitPriceMinor }, sp.maxOf { it.unitPriceMinor }, storeLast.date)
            }.sortedByDescending { s -> chainPoints.filter { it.storeId == s.storeId }.maxOf { it.occurredAt } }
            ChainPrices(chainId, last.unitPriceMinor, chainPoints.minOf { it.unitPriceMinor }, chainPoints.maxOf { it.unitPriceMinor }, last.date, stores) to last.occurredAt
        }
        .sortedByDescending { it.second }
        .map { it.first }

    /**
     * Изменение последней цены к предыдущей покупке в той же сети, в процентах (HALF_UP).
     * Сети сравниваются только сами с собой: иначе стрелка показывала бы разницу между магазинами.
     */
    fun trendPercent(points: List<PricePoint>): Int? {
        val last = points.maxByOrNull { it.occurredAt } ?: return null
        val previous = points.filter { it.chainId == last.chainId && it.occurredAt < last.occurredAt }
            .maxByOrNull { it.occurredAt } ?: return null
        if (previous.unitPriceMinor == 0L) return null
        return BigDecimal(last.unitPriceMinor - previous.unitPriceMinor).multiply(BigDecimal(100))
            .divide(BigDecimal(previous.unitPriceMinor), 0, RoundingMode.HALF_UP).toInt()
    }

    /** Список «Цены»: продукты с покупками, последние купленные — сверху. */
    fun overview(products: List<Product>, points: List<PricePoint>): List<ProductOverview> {
        val byProduct = points.groupBy { it.productId }
        return products.mapNotNull { product ->
            val own = byProduct[product.id] ?: return@mapNotNull null
            ProductOverview(product, own.maxBy { it.occurredAt }, trendPercent(own))
        }.sortedByDescending { it.last.occurredAt }
    }
}

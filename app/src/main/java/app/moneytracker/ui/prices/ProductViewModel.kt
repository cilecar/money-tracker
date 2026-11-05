package app.moneytracker.ui.prices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.moneytracker.domain.receipt.ArticleWithChain
import app.moneytracker.domain.receipt.ChainPrices
import app.moneytracker.domain.receipt.PricePoint
import app.moneytracker.domain.receipt.Prices
import app.moneytracker.domain.receipt.Product
import app.moneytracker.domain.receipt.ProductCandidate
import app.moneytracker.domain.receipt.ProductMatching
import app.moneytracker.domain.receipt.Store
import app.moneytracker.domain.repository.ReceiptRepository
import app.moneytracker.ui.navigation.ProductRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProductUiState(
    val product: Product,
    val series: List<ChartSeries>,
    val chainNames: Map<Long, String>,
    val stores: Map<Long, Store>,
    val byChain: List<ChainPrices>,
    /** Новые сверху. */
    val history: List<PricePoint>,
    val articles: List<ArticleWithChain>,
    /** Для связывания: подсказки и все продукты той же единицы, кроме этого. */
    val suggestions: List<Product>,
    val linkable: List<Product>,
)

sealed interface ProductScreenState {
    data object Loading : ProductScreenState

    /** Продукт удалён — например, слит с другим. */
    data object Gone : ProductScreenState

    data class Ready(val ui: ProductUiState) : ProductScreenState
}

@HiltViewModel
class ProductViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val receipts: ReceiptRepository,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ProductRoute>().id

    val state: StateFlow<ProductScreenState> = combine(
        receipts.observeProduct(id),
        receipts.observePricePoints(id),
        receipts.observeAllArticles(),
        receipts.observeProducts(),
        receipts.observeChains(),
    ) { product, points, allArticles, products, chains ->
        if (product == null) return@combine ProductScreenState.Gone
        val chainsByProduct = allArticles.groupBy { it.article.productId }.mapValues { (_, a) -> a.map { it.chain.id }.toSet() }
        val own = chainsByProduct[id].orEmpty()
        val sameUnit = products.filter { it.id != id && it.unit == product.unit }
        ProductScreenState.Ready(
            ProductUiState(
                product = product,
                series = chartSeries(points),
                chainNames = chains.associate { it.chain.id to it.chain.displayName },
                stores = chains.flatMap { it.stores }.associateBy { it.id },
                byChain = Prices.byChain(points),
                history = points.sortedByDescending { it.occurredAt },
                articles = allArticles.filter { it.article.productId == id },
                suggestions = ProductMatching.similar(product, own, sameUnit.map { ProductCandidate(it, chainsByProduct[it.id].orEmpty()) }),
                linkable = sameUnit,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductScreenState.Loading)

    fun rename(name: String) = viewModelScope.launch { receipts.renameProduct(id, name) }

    /** Выбранный продукт вливается в этот: его артикулы переходят сюда. */
    fun link(otherProductId: Long) = viewModelScope.launch { receipts.mergeProducts(targetId = id, sourceId = otherProductId) }

    fun unlink(articleId: Long) = viewModelScope.launch { receipts.detachArticle(articleId) }
}

package app.moneytracker.domain.receipt

data class ProductCandidate(val product: Product, val chainIds: Set<Long>)

/** Подсказки при ручном связывании товаров разных сетей. */
object ProductMatching {
    private const val MIN_TOKEN = 3

    /**
     * Продукты той же единицы, у которых есть сеть, где [target] не продаётся, и хотя бы одно общее слово
     * (от 3 букв, без регистра). Больше общих слов — выше.
     */
    fun similar(target: Product, targetChainIds: Set<Long>, candidates: List<ProductCandidate>, limit: Int = 5): List<Product> {
        val targetTokens = tokens(target.name)
        return candidates.asSequence()
            .filter { it.product.id != target.id && it.product.unit == target.unit }
            .filter { (it.chainIds - targetChainIds).isNotEmpty() }
            .map { it.product to (tokens(it.product.name) intersect targetTokens).size }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun tokens(name: String): Set<String> =
        ItemNameParser.normalize(name).split(' ', '/').filter { it.length >= MIN_TOKEN }.toSet()
}

package app.moneytracker.domain.model

/** Месячный лимит по категории (включая её подкатегории). */
data class Budget(
    val id: Long = 0,
    val categoryId: Long,
    val limit: Money,
)

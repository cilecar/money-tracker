package app.moneytracker.domain.model

enum class PaymentMethodType { CASH, CARD, TRANSFER }

data class PaymentMethod(
    val id: Long = 0,
    val name: String,
    val type: PaymentMethodType,
    /** Валюта карты: при выборе способа оплаты подставляется как валюта ввода. */
    val currency: CurrencyCode? = null,
    /** См. [Category.defaultKey]. */
    val defaultKey: String? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
)

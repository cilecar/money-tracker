package app.moneytracker.domain.model

/**
 * Поддерживаемые валюты. [fractionDigits] — сколько минорных единиц в основной
 * (у всех трёх по 2: центы, пары, копейки), от этого зависит хранение в [Money.minor].
 */
enum class CurrencyCode(val fractionDigits: Int) {
    RSD(2),
    EUR(2),
    RUB(2),
}

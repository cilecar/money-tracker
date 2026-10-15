package app.moneytracker.domain.model

/**
 * Поддерживаемые валюты. [fractionDigits] — сколько минорных единиц в основной
 * (у всех по 2: центы, пары, копейки; у USDT тоже 2 — см. ниже), от этого зависит хранение в [Money.minor].
 *
 * [isDisplay] — можно ли показывать в ней аналитику и бюджеты. Для таких валют у каждой операции есть
 * заранее посчитанная сумма (rsd/eur/rub_minor, см. [ConvertedAmounts]). USDT — только валюта ввода и счёта:
 * колонки usdt_minor нет, её курс к EUR хранится в снапшоте, и пересчёт делается по нему.
 *
 * USDT с 2 знаками: карта Bybit списывает USDT с точностью до центов; у самого токена 6 знаков,
 * но в трекере дробь мельче цента не нужна.
 *
 * [ambiguousGroupIsError] — «12.345» (один разделитель и ровно 3 цифры после него) для такой валюты
 * ошибка ввода, а не 12 345. Для RSD это удобная запись тысяч, а у USDT суммы с Bybit приходят
 * с дробью, и три знака после точки скорее означают 12,345 USDT, чем двенадцать тысяч.
 */
enum class CurrencyCode(
    val fractionDigits: Int,
    val isDisplay: Boolean = true,
    val ambiguousGroupIsError: Boolean = false,
) {
    RSD(2),
    EUR(2),
    RUB(2),
    USDT(2, isDisplay = false, ambiguousGroupIsError = true),
    ;

    companion object {
        /** Валюты аналитики, бюджетов и итогов. */
        val DISPLAY: List<CurrencyCode> = entries.filter { it.isDisplay }
    }
}

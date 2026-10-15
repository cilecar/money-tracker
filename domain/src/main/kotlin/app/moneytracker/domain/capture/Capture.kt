package app.moneytracker.domain.capture

import app.moneytracker.domain.model.Money
import app.moneytracker.domain.model.RateSnapshot
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Уведомление другого приложения — только поля, нужные парсерам. Никуда не отправляется. */
data class RawNotification(
    val packageName: String,
    /** StatusBarNotification.key: повторный пуш того же уведомления приходит с тем же ключом. */
    val key: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val postedAt: Instant,
)

/** Покупка, найденная в уведомлении. */
data class ParsedPurchase(
    val source: String,
    val amount: Money,
    val merchant: String,
    /** Последние цифры карты («1234»), если источник их показывает: по ним выбирается счёт. */
    val cardSuffix: String?,
    val occurredAt: Instant,
    val notificationKey: String,
)

/** Один парсер на источник (пакет приложения). */
interface NotificationParser {
    val packageName: String

    /** null — это не покупка (или формат не распознан). */
    fun parse(notification: RawNotification): ParsedPurchase?
}

enum class CaptureStatus { PENDING, CONFIRMED, DISMISSED }

/** Захваченная покупка. Сама в расходы не попадает — только после «Добавить» или сохранения в редакторе. */
data class CapturedPurchase(
    val id: Long = 0,
    val source: String,
    val amount: Money,
    val merchant: String,
    val merchantKey: String,
    val cardSuffix: String?,
    val occurredAt: Instant,
    val notificationKey: String,
    val status: CaptureStatus = CaptureStatus.PENDING,
    val expenseId: Long? = null,
    val createdAt: Instant,
) {
    companion object {
        fun from(p: ParsedPurchase, now: Instant) = CapturedPurchase(
            source = p.source,
            amount = p.amount,
            merchant = p.merchant,
            merchantKey = Merchants.key(p.merchant),
            cardSuffix = p.cardSuffix,
            occurredAt = p.occurredAt,
            notificationKey = p.notificationKey,
            createdAt = now,
        )
    }
}

object Merchants {
    /**
     * Ключ мерчанта для правил и дедупликации: «MAXI 123 Beograd» и «Maxi» должны совпасть
     * хотя бы по вхождению. Только буквы и цифры в нижнем регистре.
     */
    fun key(merchant: String): String = merchant.lowercase().filter { it.isLetterOrDigit() }

    /** Один и тот же мерчант: ключи равны или один содержит другой (не короче 3 символов, чтобы «a» не совпало со всем). */
    fun same(a: String, b: String): Boolean {
        if (a == b) return a.isNotEmpty()
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.length >= 3 && long.contains(short)
    }
}

/**
 * Дубль — та же покупка, пришедшая повторно: тот же ключ уведомления, или тот же мерчант и сумма
 * в окне ±5 минут. Суммы в разных валютах (банк показывает EUR, Wallet — RSD) сравниваются по курсу
 * с допуском: курс банка отличается от курса API.
 */
object PurchaseDeduplicator {
    val WINDOW: Duration = Duration.ofMinutes(5)
    val CROSS_CURRENCY_TOLERANCE: BigDecimal = BigDecimal("0.03")

    fun findDuplicate(p: ParsedPurchase, recent: List<CapturedPurchase>, rates: RateSnapshot?): CapturedPurchase? {
        recent.firstOrNull { it.notificationKey == p.notificationKey }?.let { return it }
        val key = Merchants.key(p.merchant)
        return recent.firstOrNull { c ->
            Duration.between(c.occurredAt, p.occurredAt).abs() <= WINDOW &&
                Merchants.same(c.merchantKey, key) &&
                sameAmount(p.amount, c.amount, rates)
        }
    }

    /** Без курса разные валюты не сравнить — лучше лишняя запись в «Ожидают», чем потерянная покупка. */
    fun sameAmount(a: Money, b: Money, rates: RateSnapshot?): Boolean {
        if (a.currency == b.currency) return a == b
        rates ?: return false
        val converted = rates.convert(a, b.currency)
        val diff = BigDecimal.valueOf(Math.abs(converted.minor - b.minor))
        return diff <= BigDecimal.valueOf(b.minor).multiply(CROSS_CURRENCY_TOLERANCE)
    }
}

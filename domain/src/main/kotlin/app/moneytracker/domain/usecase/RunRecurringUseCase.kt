package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.RecurringRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Создаёт расходы по регулярным шаблонам, «догоняя» пропущенные даты
 * (телефон был выключен неделю — появятся все платежи за неделю).
 *
 * Singleton + Mutex: запуск при старте приложения и фоновая задача могут совпасть
 * по времени; без блокировки оба увидели бы одну и ту же «должную» дату.
 */
@Singleton
class RunRecurringUseCase @Inject constructor(
    private val recurring: RecurringRepository,
    private val rates: ExchangeRateRepository,
    private val checkBudgets: CheckBudgetsUseCase,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** @return сколько расходов создано. */
    suspend operator fun invoke(): Int = mutex.withLock { runDue() }

    /**
     * Выполняет [block] под тем же замком, что и запуск. Нужно тем, кто перезаписывает шаблон
     * целиком: иначе параллельный запуск сдвинет nextRunDate, а запись откатит его назад (дубль).
     * Внутри [block] запускать платежи — только через [runDue] (Mutex не реентерабельный).
     */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Тело запуска без блокировки: вызывать только внутри [exclusive]. */
    suspend fun runDue(): Int {
        val today = LocalDate.now(clock)
        val zone = clock.zone
        var created = 0
        for (template in recurring.getDue(today)) {
            var item = template
            var guard = 0
            while (item.isDue(today) && guard++ < MAX_CATCH_UP) {
                val date = item.nextRunDate
                val next = item.nextAfter(date)
                val stillActive = item.endDate == null || !next.isAfter(item.endDate)
                val now = clock.instant()
                recurring.recordOccurrence(
                    expense = Expense.create(
                        amount = item.amount,
                        categoryId = item.categoryId,
                        paymentMethodId = item.paymentMethodId,
                        // Полдень: дата не «уплывёт» на соседний день при смене часового пояса.
                        occurredAt = date.atTime(LocalTime.NOON).atZone(zone).toInstant(),
                        zone = zone,
                        // Для пропущенных дат исторических курсов у нас нет — берём текущие.
                        rates = rates.current()?.snapshot,
                        now = now,
                        comment = item.comment,
                        tags = item.tags,
                        recurringId = item.id,
                    ),
                    recurringId = item.id,
                    nextRunDate = next,
                    active = stillActive,
                )
                item = item.copy(nextRunDate = next, active = stillActive)
                created++
            }
        }
        if (created > 0) checkBudgets()
        return created
    }

    private companion object {
        /** Защита от лавины: ежедневный шаблон с датой начала 5 лет назад не создаст 1800 записей. */
        const val MAX_CATCH_UP = 366
    }
}

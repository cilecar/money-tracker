package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.Expense
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.ExchangeRateRepository
import app.moneytracker.domain.repository.RecurringRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Создаёт расходы по регулярным шаблонам, «догоняя» пропущенные даты
 * (телефон был выключен неделю — появятся все платежи за неделю), но не глубже [MAX_CATCH_UP].
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
        val floor = today.minus(MAX_CATCH_UP)
        var created = 0
        for (template in recurring.getDue(today)) {
            // Что сейчас в базе: с этим значением сравнивает compare-and-set. Шаблон могли удалить,
            // поставить на паузу или заменить импортом после getDue — тогда запись не пройдёт.
            var stored = template.nextRunDate
            var item = template
            if (item.nextRunDate.isBefore(floor)) {
                // Даты старше порога пропускаем совсем, а не «по 366 за запуск»: счётчик на запуск
                // не спасал — nextRunDate оставался в прошлом, и следующий запуск продолжал догон.
                val next = item.firstOnOrAfter(floor)
                item = item.copy(nextRunDate = next, active = item.isActiveAt(next))
                // Если догонять нечего (шаблон закончился давно), сдвиг сохраняем сами; иначе его
                // запишет первый recordOccurrence ниже.
                if (!item.isDue(today)) recurring.advance(item.id, stored, item.nextRunDate, item.active)
            }
            while (item.isDue(today)) {
                val date = item.nextRunDate
                val next = item.nextAfter(date)
                val stillActive = item.isActiveAt(next)
                val now = clock.instant()
                val recorded = recurring.recordOccurrence(
                    expense = Expense.create(
                        amount = item.amount,
                        categoryId = item.categoryId,
                        accountId = item.accountId,
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
                    from = stored,
                    nextRunDate = next,
                    active = stillActive,
                )
                if (!recorded) break
                stored = next
                item = item.copy(nextRunDate = next, active = stillActive)
                created++
            }
        }
        if (created > 0) checkBudgets()
        return created
    }

    /** Будут ли ещё платежи начиная с [next]. */
    private fun RecurringExpense.isActiveAt(next: LocalDate) = endDate == null || !next.isAfter(endDate)

    private companion object {
        /** Защита от лавины: ежедневный шаблон с датой начала 5 лет назад создаст записи только за последний год. */
        val MAX_CATCH_UP: Period = Period.ofYears(1)
    }
}

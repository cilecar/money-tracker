package app.moneytracker.domain.usecase

import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.domain.repository.RecurringRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

class SaveRecurringUseCase @Inject constructor(
    private val repository: RecurringRepository,
    private val runRecurring: RunRecurringUseCase,
    private val clock: Clock,
) {
    /**
     * Новый шаблон начинает с [RecurringExpense.startDate]: если она в прошлом, пропущенные
     * платежи будут добавлены (пользователь явно выбрал дату начала).
     *
     * При редактировании с изменённым расписанием «догона» нет: следующая дата — первая
     * по новому расписанию начиная с сегодня (или с завтра, если сегодняшний платёж уже создан).
     * Иначе уже созданные платежи задублировались бы. То же при снятии с паузы: платежи
     * за время паузы не создаются.
     */
    suspend operator fun invoke(template: RecurringExpense): Long {
        require(template.amount.minor > 0) { "Amount must be positive" }
        require(template.endDate == null || !template.endDate.isBefore(template.startDate)) { "End before start" }
        // Чтение и запись шаблона — под замком запуска, иначе фоновый запуск между ними потеряется.
        return runRecurring.exclusive {
            val today = LocalDate.now(clock)
            val existing = if (template.id != 0L) repository.get(template.id) else null

            val next = when {
                existing == null -> template.startDate
                !existing.sameSchedule(template) -> template.firstOnOrAfter(
                    if (existing.ranOn(today)) today.plusDays(1) else today,
                )
                !existing.active && template.active -> maxOf(existing.nextRunDate, template.firstOnOrAfter(today))
                else -> existing.nextRunDate
            }
            val active = template.active && (template.endDate == null || !next.isAfter(template.endDate))
            val id = repository.upsert(template.copy(nextRunDate = next, active = active))
            // Если платёж приходится на сегодня — он появится в списке сразу, не дожидаясь фоновой задачи.
            runRecurring.runDue()
            id
        }
    }

    /** Сегодняшний платёж по этому расписанию уже создан: сегодня — дата расписания, и она пройдена. */
    private fun RecurringExpense.ranOn(today: LocalDate) =
        nextRunDate.isAfter(today) && firstOnOrAfter(today) == today
}

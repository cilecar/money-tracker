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
     * по новому расписанию начиная с сегодня. Иначе уже созданные платежи задублировались бы.
     */
    suspend operator fun invoke(template: RecurringExpense): Long {
        require(template.amount.minor > 0) { "Amount must be positive" }
        require(template.endDate == null || !template.endDate.isBefore(template.startDate)) { "End before start" }
        val today = LocalDate.now(clock)
        val existing = if (template.id != 0L) repository.get(template.id) else null

        val next = when {
            existing == null -> template.startDate
            existing.sameSchedule(template) -> existing.nextRunDate
            else -> template.firstOnOrAfter(today)
        }
        val active = template.active && (template.endDate == null || !next.isAfter(template.endDate))
        val id = repository.upsert(template.copy(nextRunDate = next, active = active))
        // Если платёж приходится на сегодня — он появится в списке сразу, не дожидаясь фоновой задачи.
        runRecurring()
        return id
    }
}

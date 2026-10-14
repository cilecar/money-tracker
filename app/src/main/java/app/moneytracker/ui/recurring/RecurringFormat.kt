package app.moneytracker.ui.recurring

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.moneytracker.R
import app.moneytracker.domain.model.Frequency
import app.moneytracker.domain.model.RecurringExpense
import app.moneytracker.ui.format.currentLocale
import java.time.format.DateTimeFormatter

/** «Каждый месяц, 5-го», «Каждые 2 недели», «Каждый год, 1 марта». */
@Composable
fun scheduleText(r: RecurringExpense): String {
    val locale = currentLocale()
    val day = r.dayOfMonth ?: r.startDate.dayOfMonth
    // Интервал 1 — отдельной строкой: «каждый месяц», а не «каждый 1-й месяц».
    val every = when (r.frequency) {
        Frequency.DAILY -> if (r.interval == 1) stringResource(R.string.schedule_every_day) else pluralStringResource(R.plurals.schedule_days, r.interval, r.interval)
        Frequency.WEEKLY -> if (r.interval == 1) stringResource(R.string.schedule_every_week) else pluralStringResource(R.plurals.schedule_weeks, r.interval, r.interval)
        Frequency.MONTHLY -> if (r.interval == 1) stringResource(R.string.schedule_every_month) else pluralStringResource(R.plurals.schedule_months, r.interval, r.interval)
        Frequency.YEARLY -> if (r.interval == 1) stringResource(R.string.schedule_every_year) else pluralStringResource(R.plurals.schedule_years, r.interval, r.interval)
    }
    return when (r.frequency) {
        Frequency.DAILY, Frequency.WEEKLY -> every
        Frequency.MONTHLY -> every + ", " + stringResource(R.string.schedule_day_of_month, day)
        Frequency.YEARLY -> every + ", " + r.startDate.format(DateTimeFormatter.ofPattern("d MMMM", locale))
    }
}

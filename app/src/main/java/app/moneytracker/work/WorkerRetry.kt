package app.moneytracker.work

import androidx.work.ListenableWorker.Result

/**
 * retry(), пока не исчерпаны [maxAttempts] попыток (считая первую), потом failure().
 * runAttemptCount у WorkManager начинается с 0, отсюда +1. Для периодической задачи failure()
 * её не отменяет: следующий запуск будет по расписанию.
 */
internal fun retryOrFail(runAttemptCount: Int, maxAttempts: Int): Result =
    if (runAttemptCount + 1 < maxAttempts) Result.retry() else Result.failure()

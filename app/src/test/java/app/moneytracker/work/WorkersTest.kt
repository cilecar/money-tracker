package app.moneytracker.work

import androidx.work.ListenableWorker.Result
import app.moneytracker.domain.model.RefreshResult
import app.moneytracker.domain.model.RefreshResult.Failure.Reason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class WorkersTest {

    private val network = RefreshResult.Failure(Reason.NETWORK)

    @Test
    fun `rates worker makes exactly four attempts on network errors`() {
        // runAttemptCount начинается с 0: попытки 0, 1, 2 — повтор, 3 — четвёртая и последняя.
        assertEquals(listOf(Result.retry(), Result.retry(), Result.retry(), Result.failure()), (0..3).map { RatesSyncWorker.resultFor(network, it) })
    }

    @Test
    fun `rates worker does not retry junk responses and succeeds when rates are fresh`() {
        assertEquals(Result.failure(), RatesSyncWorker.resultFor(RefreshResult.Failure(Reason.BAD_RESPONSE), 0))
        assertEquals(Result.success(), RatesSyncWorker.resultFor(null, 0))
    }

    @Test
    fun `recurring worker retries a failed run`() = runTest {
        assertEquals(Result.retry(), RecurringWorker.runOrRetry(0) { throw IOException("database is locked") })
        assertEquals(Result.failure(), RecurringWorker.runOrRetry(3) { throw IOException("database is locked") })
        assertEquals(Result.success(), RecurringWorker.runOrRetry(0) {})
    }
}

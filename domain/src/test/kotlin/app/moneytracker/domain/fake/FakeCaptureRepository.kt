package app.moneytracker.domain.fake

import app.moneytracker.domain.capture.CaptureStatus
import app.moneytracker.domain.capture.CapturedPurchase
import app.moneytracker.domain.repository.CaptureRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

class FakeCaptureRepository : CaptureRepository {
    val items = MutableStateFlow<List<CapturedPurchase>>(emptyList())
    val rules = mutableMapOf<String, Long>()
    private var nextId = 1L

    override fun observePending() = items.map { list -> list.filter { it.status == CaptureStatus.PENDING } }
    override suspend fun get(id: Long) = items.value.firstOrNull { it.id == id }
    override suspend fun getBetween(from: Instant, to: Instant) = items.value.filter { it.occurredAt in from..to }
    override suspend fun insert(purchase: CapturedPurchase): Long {
        val id = nextId++
        items.value += purchase.copy(id = id)
        return id
    }
    override suspend fun setStatus(id: Long, status: CaptureStatus, expenseId: Long?) {
        items.value = items.value.map { if (it.id == id) it.copy(status = status, expenseId = expenseId ?: it.expenseId) else it }
    }
    override suspend fun ruleFor(merchantKey: String) = rules[merchantKey]
    override suspend fun saveRule(merchantKey: String, categoryId: Long) {
        rules[merchantKey] = categoryId
    }
}

package app.moneytracker.domain.usecase

import app.moneytracker.domain.fake.FakePaymentMethodRepository
import app.moneytracker.domain.fake.FakeSettingsRepository
import app.moneytracker.domain.model.CurrencyCode
import app.moneytracker.domain.model.PaymentMethod
import app.moneytracker.domain.model.PaymentMethodType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentMethodUseCasesTest {

    private val repo = FakePaymentMethodRepository(
        listOf(
            PaymentMethod(id = 1, name = "Наличные", type = PaymentMethodType.CASH),
            PaymentMethod(id = 2, name = "Карта RSD", type = PaymentMethodType.CARD, currency = CurrencyCode.RSD, sortOrder = 1),
        ),
    )
    private val settings = FakeSettingsRepository()
    private val save = SavePaymentMethodUseCase(repo)
    private val delete = DeletePaymentMethodUseCase(repo, settings)
    private val archive = ArchivePaymentMethodUseCase(repo, settings)

    private fun PaymentMethodResult.error() = (this as? PaymentMethodResult.Error)?.error

    @Test
    fun `save validates name`() = runTest {
        assertEquals(PaymentMethodError.EMPTY_NAME, save(PaymentMethod(name = " ", type = PaymentMethodType.CARD)).error())
        assertEquals(PaymentMethodError.DUPLICATE_NAME, save(PaymentMethod(name = "карта rsd", type = PaymentMethodType.CARD)).error())
        val id = (save(PaymentMethod(name = "Карта ₽", type = PaymentMethodType.CARD, currency = CurrencyCode.RUB)) as PaymentMethodResult.Success).id
        assertEquals(2, repo.get(id)!!.sortOrder)
    }

    @Test
    fun `deleting default method clears the setting`() = runTest {
        settings.setDefaultPaymentMethod(2)
        repo.expenseCounts[2] = 3
        assertEquals(PaymentMethodError.NEEDS_TARGET, delete(2).error())
        assertTrue(delete(2, moveTo = 1) is PaymentMethodResult.Success)
        assertEquals(3, repo.expenseCounts[1])
        assertNull(settings.settings.value.defaultPaymentMethodId)
    }

    @Test
    fun `archiving default method clears the setting`() = runTest {
        settings.setDefaultPaymentMethod(1)
        archive(1, true)
        assertNull(settings.settings.value.defaultPaymentMethodId)
    }

    @Test
    fun `last active method is protected`() = runTest {
        archive(1, true)
        assertEquals(PaymentMethodError.LAST_ACTIVE, archive(2, true).error())
        assertEquals(PaymentMethodError.LAST_ACTIVE, delete(2).error())
    }
}

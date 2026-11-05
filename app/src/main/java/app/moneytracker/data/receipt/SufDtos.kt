package app.moneytracker.data.receipt

import kotlinx.serialization.Serializable

/** Ответ `GET <ссылка>` с `Accept: application/json` — шапка чека. Лишние поля игнорируются. */
@Serializable
internal data class SufInvoiceDto(
    val invoiceRequest: SufInvoiceRequestDto,
    val invoiceResult: SufInvoiceResultDto,
    val journal: String? = null,
    val isValid: Boolean,
)

@Serializable
internal data class SufInvoiceRequestDto(
    val taxId: String,
    val businessName: String,
    val locationName: String,
    val address: String = "",
    val city: String = "",
)

@Serializable
internal data class SufInvoiceResultDto(val totalAmount: Double, val invoiceNumber: String, val sdcTime: String)

/** Ответ `POST /specifications`. */
@Serializable
internal data class SufSpecificationsDto(val success: Boolean, val items: List<SufItemDto> = emptyList())

@Serializable
internal data class SufItemDto(
    val name: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double,
    val labelRate: Int? = null,
)

package io.bluetape4k.workshop.commerce.metering.web

import java.math.BigDecimal
import java.time.Instant

data class RegisterMeterRequest(val code: String, val unit: String, val description: String? = null)

data class ActivatePriceRequest(val currency: String, val unitPrice: BigDecimal, val effectiveFrom: Instant)

data class RepairPriceGapRequest(
    val meterCode: String,
    val currency: String,
    val unitPrice: BigDecimal,
    val effectiveFrom: Instant,
    val effectiveTo: Instant,
)

data class IngestUsageRequest(
    val sourceSystem: String,
    val sourceEventId: String,
    val meterCode: String,
    val quantity: BigDecimal,
    val occurredAt: Instant,
    val correlationId: String? = null,
)

data class CreateBillingPeriodRequest(val currency: String, val startsAt: Instant, val endsAt: Instant)

data class CreditAdjustmentRequest(val originalEntryId: String, val reason: String)

data class ReconciliationRepairRequest(val expectedDigest: String, val currency: String)

data class ApiError(val code: String, val message: String)

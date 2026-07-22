package io.bluetape4k.workshop.commerce.metering.eventsourcing.domain

import java.math.BigDecimal
import java.time.Instant

data class UsageAccepted(
    val sourceSystem: String,
    val sourceEventId: String,
    val meterCode: String,
    val quantity: BigDecimal,
    val occurredAt: Instant,
) : DomainEvent {
    override val eventType: String = "usage.accepted"
    override val schemaVersion: Int = 1

    init {
        require(sourceSystem.isNotBlank()) { "source_system_invalid" }
        require(sourceEventId.isNotBlank()) { "source_event_id_invalid" }
        require(meterCode.isNotBlank()) { "meter_code_invalid" }
        require(quantity.signum() > 0) { "usage_quantity_invalid" }
    }
}

data class UsageRated(
    val usageEventId: String,
    val meterCode: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val amount: BigDecimal,
    val currency: String,
) : DomainEvent {
    override val eventType: String = "usage.rated"
    override val schemaVersion: Int = 1
}

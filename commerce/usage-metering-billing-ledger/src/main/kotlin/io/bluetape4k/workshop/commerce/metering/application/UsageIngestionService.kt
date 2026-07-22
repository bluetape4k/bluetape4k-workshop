package io.bluetape4k.workshop.commerce.metering.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.metering.config.MeteringProperties
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.SourceEventId
import io.bluetape4k.workshop.commerce.metering.domain.SourceSystem
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UsageQuantity
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import io.bluetape4k.workshop.commerce.metering.persistence.MeterRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEventRepository
import io.bluetape4k.workshop.commerce.metering.persistence.UsageEvents
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class UsageIngestionCommand(
    val tenantId: TenantId,
    val sourceSystem: SourceSystem,
    val sourceEventId: SourceEventId,
    val meterCode: MeterCode,
    val quantity: UsageQuantity,
    val occurredAt: Instant,
    val actor: String,
    val correlationId: String?,
    val requestFingerprint: Sha256Digest,
)

data class UsageEventView(
    val id: UUID,
    val receivedAt: Instant,
    val replayedProducerEvent: Boolean,
)

@Service
class UsageIngestionService(
    private val meters: MeterRepository,
    private val usageEvents: UsageEventRepository,
    private val properties: MeteringProperties,
    private val clock: Clock,
) {
    @Transactional
    fun ingest(command: UsageIngestionCommand): UsageEventView {
        val now = clock.instant()
        require(command.occurredAt >= now.minus(properties.occurredAtRetention)) { "occurred_at_too_old" }
        require(command.occurredAt <= now.plus(properties.occurredAtFutureSkew)) { "occurred_at_in_future" }
        val meter = requireNotNull(meters.find(command.tenantId.value, command.meterCode.value)) { "meter_not_found" }
        val id = Uuid.V7.nextId()
        val inserted = UsageEvents.insertIgnore {
            it[UsageEvents.id] = id
            it[tenantId] = command.tenantId.value
            it[sourceSystem] = command.sourceSystem.value
            it[sourceEventId] = command.sourceEventId.value
            it[requestFingerprint] = command.requestFingerprint.value
            it[meterId] = meter.id.value
            it[quantity] = command.quantity.value
            it[occurredAt] = command.occurredAt
            it[receivedAt] = now
            it[acceptedActor] = command.actor
            it[correlationId] = command.correlationId
        }.insertedCount == 1
        val usage = requireNotNull(
            usageEvents.findSource(command.tenantId.value, command.sourceSystem.value, command.sourceEventId.value),
        )
        if (!inserted) {
            require(usage.requestFingerprint == command.requestFingerprint.value) { "source_event_conflict" }
        }
        return UsageEventView(usage.id.value, usage.receivedAt, replayedProducerEvent = !inserted)
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.usage.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.AcceptUsageCommand
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.MissingPriceEvidence
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceJournal
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageAcceptanceResult
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageRecord
import io.bluetape4k.workshop.commerce.usagebilling.usage.domain.UsageSourceConflict
import io.bluetape4k.workshop.commerce.usagebilling.usage.integration.UsageIntegrationEnvelope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class UsageCommandService(
    private val journal: UsageAcceptanceJournal,
    private val clock: Clock,
    private val nextEventId: () -> UUID = Uuid.V7::nextId,
) {
    @Transactional
    fun accept(command: AcceptUsageCommand): UsageAcceptanceResult {
        val fingerprint = command.fingerprint()
        journal.findUsage(command.tenantId, command.sourceSystem, command.sourceEventId)?.let { existing ->
            if (existing.fingerprint != fingerprint) throw UsageSourceConflict()
            return UsageAcceptanceResult(existing.usageId, existing.eventId, replayed = true)
        }
        val evidence = journal.priceEvidence(command.tenantId, command.meterCode, command.currency)
            ?: throw MissingPriceEvidence()
        val now = clock.instant()
        val eventId = nextEventId()
        val usage = UsageRecord(
            usageId = Uuid.V7.nextId(),
            eventId = eventId,
            tenantId = command.tenantId,
            sourceSystem = command.sourceSystem,
            sourceEventId = command.sourceEventId,
            fingerprint = fingerprint,
            meterCode = command.meterCode,
            currency = command.currency,
            quantity = command.quantity,
            unitPrice = evidence.unitPrice,
            occurredAt = command.occurredAt,
        )
        val envelope = UsageIntegrationEnvelope.create(
            eventId = eventId,
            eventType = "UsageAccepted",
            schemaVersion = 1,
            tenantId = command.tenantId,
            aggregateId = command.sourceEventId,
            aggregateVersion = 1,
            payload = """{"usageId":"${usage.usageId}"}""",
            occurredAt = command.occurredAt,
            recordedAt = now,
        )
        val wirePayload = envelope.wirePayload()
        journal.append(
            usage,
            UsageOutboxRecord(
                eventId,
                envelope.eventType,
                envelope.partitionKey(),
                wirePayload,
                envelope.wirePayloadDigest(),
                now,
            ),
        )
        return UsageAcceptanceResult(usage.usageId, eventId, replayed = false)
    }
}

package io.bluetape4k.workshop.commerce.usagebilling.meter.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.ActivatePriceCommand
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterActivationResult
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandJournal
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterCommandReceipt
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterIdempotencyConflict
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterOutboxRecord
import io.bluetape4k.workshop.commerce.usagebilling.meter.domain.MeterPriceVersion
import io.bluetape4k.workshop.commerce.usagebilling.meter.integration.MeterIntegrationEnvelope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class MeterCommandService(
    private val journal: MeterCommandJournal,
    private val clock: Clock,
    private val nextEventId: () -> UUID = Uuid.V7::nextId,
) {
    @Transactional
    fun activatePrice(command: ActivatePriceCommand): MeterActivationResult {
        val fingerprint = command.fingerprint()
        journal.findReceipt(command.idempotencyKey)?.let { existing ->
            if (existing.fingerprint != fingerprint) throw MeterIdempotencyConflict()
            return existing.result.copy(replayed = true)
        }

        val now = clock.instant()
        val eventId = nextEventId()
        val priceVersion = MeterPriceVersion(
            priceVersionId = Uuid.V7.nextId(),
            tenantId = command.tenantId,
            meterCode = command.meterCode,
            currency = command.currency,
            unitPrice = command.unitPrice,
            effectiveAt = command.effectiveAt,
            createdAt = now,
        )
        val envelope = MeterIntegrationEnvelope.create(
            eventId = eventId,
            eventType = "PriceActivated",
            schemaVersion = 1,
            tenantId = command.tenantId,
            aggregateId = command.meterCode,
            aggregateVersion = 1,
            payload = priceActivatedPayload(command, priceVersion),
            occurredAt = command.effectiveAt,
            recordedAt = now,
        )
        val result = MeterActivationResult(priceVersion.priceVersionId, eventId, replayed = false)
        journal.append(
            receipt = MeterCommandReceipt(command.idempotencyKey, fingerprint, result),
            priceVersion = priceVersion,
            outboxRecord = MeterOutboxRecord(
                eventId = eventId,
                eventType = envelope.eventType,
                partitionKey = envelope.partitionKey(),
                payload = envelope.payload,
                payloadDigest = envelope.payloadDigest,
                createdAt = now,
            ),
        )
        return result
    }

    private fun priceActivatedPayload(
        command: ActivatePriceCommand,
        priceVersion: MeterPriceVersion,
    ): String =
        """{"meterCode":"${command.meterCode}",""" +
            """"currency":"${command.currency}",""" +
            """"unitPrice":"${priceVersion.unitPrice}"}"""
}

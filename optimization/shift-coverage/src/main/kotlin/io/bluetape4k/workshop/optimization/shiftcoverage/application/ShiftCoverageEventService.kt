package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.GenerationId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.PlanId
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.ShiftCoverageEventType
import java.time.Clock

/** event type별 mutation은 inbox claim 이후에만 실행되는 facade입니다. */
class ShiftCoverageEventService(
    private val inbox: ShiftCoverageInboxService,
    private val plans: ShiftCoveragePlanStore? = null,
    private val generations: ShiftCoverageGenerationStore? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun receive(
        type: ShiftCoverageEventType,
        provider: ShiftCoverageProvider,
        eventId: EventId,
        digest: String,
        providerRevision: Long,
    ): ShiftCoverageInboxRecord = inbox.claim(
        ShiftCoverageInboxEvent(provider, eventId, digest, providerRevision),
    ).also {
        require(type.wireName.isNotBlank()) { "event type must have a wire name" }
    }

    fun receive(event: ShiftCoverageDomainEvent): ShiftCoverageEventResult {
        val record = receive(event.type, event.provider, event.eventId, event.digest, event.providerRevision)
        val firstDelivery = record.status == ShiftCoverageInboxStatus.RECEIVED
        val planStaled = firstDelivery && (plans?.markStale(event.planId, event.planRevision) == true)
        val generationStaled = if (firstDelivery) {
            generations?.find(event.generationId)?.let {
                generations.markStale(event.generationId, clock.instant(), "${event.type.wireName} changed source state")
                true
            } ?: false
        } else false
        return ShiftCoverageEventResult(record, planStaled, generationStaled)
    }
}

data class ShiftCoverageDomainEvent(
    val type: ShiftCoverageEventType,
    val provider: ShiftCoverageProvider,
    val eventId: EventId,
    val digest: String,
    val providerRevision: Long,
    val planId: PlanId,
    val planRevision: Long,
    val generationId: GenerationId,
)

data class ShiftCoverageEventResult(
    val inbox: ShiftCoverageInboxRecord,
    val planStaled: Boolean,
    val generationStaled: Boolean,
)

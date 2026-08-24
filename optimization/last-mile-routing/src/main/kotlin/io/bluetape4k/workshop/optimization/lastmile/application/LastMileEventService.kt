package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.optimization.lastmile.domain.EventId
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEvent
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventCanonicalizer
import io.bluetape4k.workshop.optimization.lastmile.domain.LastMileEventType
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileEventAppendResult
import io.bluetape4k.workshop.optimization.lastmile.persistence.LastMileRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal data class LastMileEventCommand(
    val type: LastMileEventType,
    val aggregateId: String,
    val eventKey: String,
    val payload: Map<String, String>,
    val occurredAt: Instant? = null,
)

internal data class LastMileEventReceipt(
    val event: LastMileEvent,
    val appendResult: LastMileEventAppendResult,
    val requestGeneration: Long,
    val latestDigest: String,
)

/** canonical event 저장과 traffic/pickup burst generation coalescing을 담당합니다. */
@Service
internal class LastMileEventService(
    private val repository: LastMileRepository,
    private val clock: Clock,
) {
    private val generations = ConcurrentHashMap<String, CoalescingState>()

    @Transactional
    fun append(command: LastMileEventCommand): LastMileEventReceipt {
        val canonicalPayload = LastMileEventCanonicalizer.canonicalize(
            type = command.type,
            aggregateId = command.aggregateId,
            eventKey = command.eventKey,
            payload = command.payload,
        )
        val event = LastMileEvent(
            eventId = EventId(Uuid.V7.nextIdAsString()),
            type = command.type,
            aggregateId = command.aggregateId,
            eventKey = command.eventKey,
            occurredAt = command.occurredAt ?: Instant.now(clock),
            canonicalPayload = canonicalPayload,
        )
        val appendResult = repository.appendEvent(event)
        val coalescingKey = "${command.aggregateId}|${coalescingGroup(command.type)}"
        val state = generations.compute(coalescingKey) { _, current ->
            when {
                appendResult == LastMileEventAppendResult.DIGEST_CONFLICT -> current ?: CoalescingState(0L, event.digest)
                current == null -> CoalescingState(1L, event.digest)
                appendResult == LastMileEventAppendResult.DUPLICATE -> current
                else -> current.copy(generation = current.generation + 1L, latestDigest = event.digest)
            }
        } ?: CoalescingState(0L, event.digest)
        return LastMileEventReceipt(
            event = event,
            appendResult = appendResult,
            requestGeneration = state.generation,
            latestDigest = state.latestDigest,
        )
    }

    fun latest(aggregateId: String, type: LastMileEventType): Pair<Long, String>? =
        generations["$aggregateId|${coalescingGroup(type)}"]?.let { it.generation to it.latestDigest }

    private fun coalescingGroup(type: LastMileEventType): String = when (type) {
        LastMileEventType.TRAFFIC_DURATION_UPDATED,
        LastMileEventType.PICKUP_WINDOW_CHANGED,
        -> "REPLAN_BURST"

        else -> type.name
    }

    private data class CoalescingState(
        val generation: Long,
        val latestDigest: String,
    )
}

package io.bluetape4k.workshop.commerce.metering.eventsourcing.application

import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentDirection
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentPosted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.AdjustmentState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageRated
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventStore
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OccurredEventCursor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayTelemetry
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

@Service
class AdjustmentCommandService(
    private val eventStore: EventStore,
    private val codec: DomainEventJsonCodec,
    metrics: ReplayTelemetry? = null,
) {
    private val replayRuntime = ReplayRuntime(eventStore, codec, metrics)

    fun post(tenantId: String, adjustmentId: String, adjustment: AdjustmentPosted, now: Instant): Boolean {
        val stream = StreamKey(tenantId, "Adjustment", adjustmentId)
        val current = replay(stream, AdjustmentState.Empty, AdjustmentReducer, replayRuntime)
        if (current.state is AdjustmentState.Posted) {
            check(current.state.adjustment == adjustment) { "adjustment_conflict" }
            return false
        }
        eventStore.append(stream, 0, listOf(codec.encode(adjustment, now)))
        return true
    }
}

data class ReconciliationQuery(
    val tenantId: String,
    val projectionName: String,
    val startsAt: Instant,
    val endsAt: Instant,
)

enum class ReconciliationFindingType { AMOUNT_MISMATCH }

data class ReconciliationFinding(
    val type: ReconciliationFindingType,
    val expectedAmount: BigDecimal,
    val actualAmount: BigDecimal,
    val generation: Int,
    val observedPosition: Long,
    val digest: String,
)

@Service
class ReconciliationService(
    private val eventStore: EventStore,
    private val codec: DomainEventJsonCodec,
    private val generations: ProjectionGenerationRepository,
    private val readModels: BillingReadModelRepository,
    private val metrics: BillingTelemetry? = null,
) {
    fun inspect(query: ReconciliationQuery): ReconciliationFinding? {
        val active = checkNotNull(generations.active(query.projectionName)) { "active_projection_missing" }
        val expected = financialEvents(query).fold(BigDecimal.ZERO) { total, persisted ->
            when (val event = codec.registry.decode(persisted.eventType, persisted.schemaVersion, persisted.payload)) {
                is UsageRated -> total + event.amount
                is AdjustmentPosted -> if (event.direction == AdjustmentDirection.CREDIT) {
                    total - event.amount
                } else {
                    total + event.amount
                }
                else -> total
            }
        }
        val actual = readModels.financialTotal(query.projectionName, active.generation, query.tenantId)
        if (expected.compareTo(actual) == 0) return null
        val material = listOf(
            query.tenantId,
            query.projectionName,
            active.generation,
            active.checkpoint,
            expected.toPlainString(),
            actual.toPlainString(),
        ).joinToString("|")
        return ReconciliationFinding(
            ReconciliationFindingType.AMOUNT_MISMATCH,
            expected,
            actual,
            active.generation,
            active.checkpoint,
            sha256(material),
        ).also { metrics?.recordReconciliation(it.type.name.lowercase()) }
    }

    fun isStillCurrent(query: ReconciliationQuery, finding: ReconciliationFinding): Boolean =
        inspect(query)?.let { current ->
            current.digest == finding.digest &&
                current.generation == finding.generation &&
                current.observedPosition == finding.observedPosition
        } == true

    private fun financialEvents(query: ReconciliationQuery): List<PersistedEvent> =
        loadAll(query, "usage.rated") + loadAll(query, "adjustment.posted")

    private fun loadAll(query: ReconciliationQuery, eventType: String): List<PersistedEvent> {
        val events = mutableListOf<PersistedEvent>()
        var cursor: OccurredEventCursor? = null
        do {
            val page = eventStore.loadByType(
                EventTypeQuery(query.tenantId, eventType, query.startsAt, query.endsAt, cursor, EVENT_PAGE_SIZE),
            )
            events += page
            cursor = page.lastOrNull()?.let { OccurredEventCursor(it.occurredAt, it.eventId) }
        } while (page.size == EVENT_PAGE_SIZE)
        return events
    }

    private fun sha256(material: String): String = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    private companion object {
        const val EVENT_PAGE_SIZE = 1_000
    }
}

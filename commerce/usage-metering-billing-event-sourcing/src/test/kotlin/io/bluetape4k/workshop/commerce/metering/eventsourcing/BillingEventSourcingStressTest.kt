package io.bluetape4k.workshop.commerce.metering.eventsourcing

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingCloseBatchResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.BillingLifecycleCommandService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.DomainEventJsonCodec
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.MeterCommandService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.DomainEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterReducer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.MeterState
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PersistedEvent
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.PricePoint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.StreamKey
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.UsageAccepted
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateReplayer
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.AggregateSnapshotSeed
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.EventTypeQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OccurredEventCursor
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.ReplayPolicy
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.StreamAppend
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreDatabaseFixture
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.NewAggregateSnapshot
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionCheckpointRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.SnapshotRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionCoordinator
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionEventContext
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionHandlers
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionModelType
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.*

@Tag("stress")
class BillingEventSourcingStressTest {

    companion object: KLogging() {
        const val TENANT = "tenant-stress"
        const val METER = "api_calls"
        const val PERIOD = "2026-07"
        const val CURRENCY = "USD"
        const val PROJECTION = "billing"
        const val USAGE_COUNT = 10_000
        const val APPEND_BATCH_SIZE = 250
        const val CLOSE_BATCH_SIZE = 1_000
        const val PROJECTION_BATCH_SIZE = 100
        const val QUERY_PAGE_SIZE = 1_000
        const val REDUCER_VERSION = 1
        const val SHA256_HEX_LENGTH = 64
        const val MILLIS_PER_SECOND = 1_000.0
        val START: Instant = Instant.parse("2026-07-01T00:00:00Z")
        val END: Instant = START + 3_600.seconds()
        val UNIT_PRICE: BigDecimal = BigDecimal("0.10")
        val EXPECTED_TOTAL: BigDecimal = BigDecimal("1000.00")
        val LEASE_DURATION: Duration = 30.minutes()
    }

    private val fixture = EventStoreDatabaseFixture()
    private val eventStore = EventStoreRepository()
    private val codec = DomainEventJsonCodec()
    private val generations = ProjectionGenerationRepository()
    private val checkpoints = ProjectionCheckpointRepository()
    private val readModels = BillingReadModelRepository()
    private val handlers = ProjectionHandlers(readModels)
    private val coordinator = ProjectionCoordinator(checkpoints)

    @Test
    fun `ten thousand usage events survive close replay and generation rebuild`() {
        fixture.reset()
        val startedAt = System.nanoTime()
        seedAuthority()
        appendUsageEvents()
        closeBillingPeriod()

        val rated = loadAll("usage.rated")
        rated shouldHaveSize USAGE_COUNT
        rated.map { it.eventId }.distinct() shouldHaveSize USAGE_COUNT
        rated.all { it.previousHash == null && it.eventHash.length == SHA256_HEX_LENGTH }.shouldBeTrue()

        verifySnapshotReplay()
        val highWatermark = lastGlobalPosition()
        rebuildAndSwitch(highWatermark)

        val entries = fixture.executor.transaction {
            readModels.entries(PROJECTION, 2, TENANT)
        }
        val charges = entries.filter {
            it.modelType == ProjectionModelType.LEDGER_DEBIT
        }
        charges.size.shouldBeEqualTo(USAGE_COUNT)
        charges.map { it.provenance }.distinct() shouldHaveSize USAGE_COUNT
        EXPECTED_TOTAL.compareTo(charges.sumOf { checkNotNull(it.amount) }) shouldBeEqualTo 0

        val reconciliation = ReconciliationService(eventStore, codec, generations, readModels)
        val finding = fixture.executor.transaction {
            val query = ReconciliationQuery(TENANT, PROJECTION, START.minusSeconds(1), END + 1.seconds())
            reconciliation.inspect(query)
        }
        finding.shouldBeNull()
        fixture.executor.transaction {
            generations.active(PROJECTION)?.generation
        } shouldBeEqualTo 2

        reportThroughput(startedAt)
    }

    private fun seedAuthority() {
        val meters = MeterCommandService(eventStore, codec)
        val billing = BillingLifecycleCommandService(eventStore, codec)

        fixture.executor.transaction {
            meters.register(TENANT, METER, "request", CURRENCY, START)
            meters.activatePrice(TENANT, METER, UNIT_PRICE, CURRENCY, START)
            billing.open(TENANT, PERIOD, CURRENCY, START, END)
            billing.startClose(TENANT, PERIOD, END)
        }
    }

    private fun appendUsageEvents() {
        (0 until USAGE_COUNT).chunked(APPEND_BATCH_SIZE).forEach { indexes ->
            val events = indexes.map { index ->
                val occurredAt = START + (index.toLong() + 1).millis()
                val usage = UsageAccepted(
                    sourceSystem = "stress-fixture",
                    sourceEventId = "usage-$index",
                    meterCode = METER,
                    quantity = BigDecimal.ONE,
                    occurredAt = occurredAt
                )
                StreamAppend(
                    stream = StreamKey(TENANT, "Usage", "usage-$index"),
                    expectedVersion = 0,
                    events = listOf(codec.encode(usage, occurredAt)),
                )
            }

            log.debug { "append events. size=${events.size}" }
            fixture.executor.transaction {
                eventStore.appendAll(events)
            }
        }
    }

    private fun closeBillingPeriod() {
        val closer = BillingCloseService(eventStore, codec)
        var batches = 0
        var result: BillingCloseBatchResult
        do {
            result = fixture.executor.transaction {
                closer.closeNextBatch(TENANT, PERIOD, CLOSE_BATCH_SIZE, END + 1.seconds())
            }
            if (result == BillingCloseBatchResult.APPLIED) batches += 1
            log.debug { "result=$result" }
        } while (result == BillingCloseBatchResult.APPLIED || result == BillingCloseBatchResult.RETRY)

        batches shouldBeEqualTo USAGE_COUNT / CLOSE_BATCH_SIZE
    }

    private fun verifySnapshotReplay() {
        val meterStream = StreamKey(TENANT, "Meter", METER)
        val meterEvents = fixture.executor.transaction { eventStore.load(meterStream) }
        val snapshot = NewAggregateSnapshot(
            stream = meterStream,
            streamVersion = meterEvents.size.toLong(),
            reducerVersion = REDUCER_VERSION,
            statePayload = """{"meterCode":"$METER","unit":"request","currency":"$CURRENCY"}""",
            lastEventHash = meterEvents.last().eventHash,
            createdAt = END,
        )
        val stored = fixture.executor.transaction {
            SnapshotRepository().append(snapshot)
        }
        stored.shouldNotBeNull()
        val state = MeterState.Active(
            meterCode = METER,
            unit = "request",
            currency = CURRENCY,
            prices = listOf(PricePoint(CURRENCY, UNIT_PRICE, START))
        )
        val replayed = AggregateReplayer.replay(
            events = meterEvents,
            initialState = MeterState.Empty,
            reducer = MeterReducer,
            snapshot = AggregateSnapshotSeed(state, stored.streamVersion, stored.lastEventHash, stored.reducerVersion),
            policy = ReplayPolicy(codec.registry, REDUCER_VERSION),
        )
        replayed.state shouldBeEqualTo state
        replayed.streamVersion shouldBeEqualTo meterEvents.size.toLong()
    }

    private fun rebuildAndSwitch(highWatermark: Long) {
        fixture.executor.transaction {
            generations.createInitialActive(PROJECTION, 1, END)
            generations.createBuilding(PROJECTION, 2, highWatermark, END)
        }

        val lease = projectGeneration(generation = 2, highWatermark = highWatermark, release = false)
        fixture.executor.transaction {
            generations.switchActive(lease, expectedActiveGeneration = 1, END + 2.seconds()).shouldBeTrue()
            checkpoints.releaseLease(lease, END + 2.seconds())
        }
    }

    private fun projectGeneration(
        generation: Int,
        highWatermark: Long,
        release: Boolean = true,
    ): io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionLease {
        val lease = fixture.executor.transaction {
            checkpoints.acquireLease(
                projectionName = PROJECTION,
                generation = generation,
                ownerToken = UUID.randomUUID(),
                now = END,
                duration = LEASE_DURATION
            )
                .shouldNotBeNull()
                .also {
                    checkpoints.raiseHighWatermark(it, highWatermark, END)
                }
        }
        while (true) {
            val applied = fixture.executor.transaction {
                val checkpoint = checkpoints.requireOwnership(lease).checkpoint
                val page = eventStore.loadAfterGlobalPosition(checkpoint, PROJECTION_BATCH_SIZE)

                page.forEach { persisted ->
                    coordinator.apply(lease, persisted.eventId, persisted.globalPosition) {
                        val event = codec.registry.decode(
                            eventType = persisted.eventType,
                            schemaVersion = persisted.schemaVersion,
                            payload = persisted.payload,
                        ).shouldBeInstanceOf<DomainEvent>()

                        handlers.handle(
                            ProjectionEventContext(
                                projectionName = PROJECTION,
                                generation = generation,
                                tenantId = persisted.stream.tenantId,
                                eventId = persisted.eventId,
                                globalPosition = persisted.globalPosition,
                                occurredAt = persisted.occurredAt,
                            ),
                            event,
                        )
                    }
                }
                page.size
            }
            log.debug { "applied=$applied" }
            if (applied == 0) break
        }
        if (release) fixture.executor.transaction {
            checkpoints.releaseLease(lease, END + 2.seconds())
        }
        return lease
    }

    @Suppress("SameParameterValue")
    private fun loadAll(eventType: String): List<PersistedEvent> {
        val events = mutableListOf<PersistedEvent>()
        var cursor: OccurredEventCursor? = null
        do {
            val page = fixture.executor.transaction {
                eventStore.loadByType(
                    EventTypeQuery(
                        tenantId = TENANT,
                        eventType = eventType,
                        startsAt = START,
                        endsAt = END + 2.seconds(),
                        after = cursor,
                        limit = QUERY_PAGE_SIZE
                    ),
                )
            }
            events += page
            cursor = page.lastOrNull()?.let { OccurredEventCursor(it.occurredAt, it.eventId) }
        } while (page.size == QUERY_PAGE_SIZE)
        return events
    }

    private fun lastGlobalPosition(): Long {
        var position = 0L
        while (true) {
            val page = fixture.executor.transaction {
                eventStore.loadAfterGlobalPosition(position, QUERY_PAGE_SIZE)
            }
            log.debug { "position=$position" }
            if (page.isEmpty()) return position
            position = page.last().globalPosition
        }
    }

    private fun reportThroughput(startedAt: Long) {
        val elapsed = (System.nanoTime() - startedAt).nanos()
        val throughput = USAGE_COUNT.toDouble() / elapsed.toMillis().coerceAtLeast(1) * MILLIS_PER_SECOND
        println(
            "stress.usage.count=$USAGE_COUNT stress.elapsed.ms=${elapsed.toMillis()} " +
                    "stress.throughput.events_per_second=${"%.2f".format(Locale.ROOT, throughput)} " +
                    "stress.peak.batch=$PROJECTION_BATCH_SIZE",
        )
    }
}

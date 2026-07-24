package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedMetrics
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedOperationalState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ActiveProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.AppendFences
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.CampaignProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventLog
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRepository
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExposedEventStoreTransactionRunner
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionCheckpoints
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionGenerations
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionLeases
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionPoisonEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionProcessedEvents
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ProjectionReadModels
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamHeads
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.awaitility.Awaitility.await
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedProjectionRuntimeIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-runtime")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() = transaction(database) { SchemaUtils.create(*TABLES) }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(*TABLES) }

    @Test
    fun `runtime projects active events validates a rebuild and recovers cancellation`() {
        appendEvents()
        val leases = ProjectionLeaseRepository()
        val rebuilds = ProjectionRebuildRepository()
        val projections = ProjectionRepository(leases)
        val operationalState = EventSourcedOperationalState()
        val registry = SimpleMeterRegistry()
        val telemetry = ProjectionRuntimeTelemetry(operationalState, EventSourcedMetrics(registry))
        val runtime = runtime(leases, rebuilds, projections, telemetry)

        try {
            runtime.start()
            awaitActiveCheckpoint(projections, INITIAL_TARGET_POSITION)
            appendThirdEvent()
            awaitActiveCheckpoint(projections, FINAL_TARGET_POSITION)
            val candidate =
                transaction(database) {
                    rebuilds.start(VOUCHER_LIFECYCLE_PROJECTION, INITIAL_TARGET_POSITION, Clock.systemUTC().instant())
                }
            awaitActivated(candidate)
            transaction(database) {
                findGeneration(candidate.key)?.targetPosition
            } shouldBeEqualTo FINAL_TARGET_POSITION
            operationalState.isProjectionDegraded() shouldBeEqualTo false
            operationalState.rebuildState() shouldBeEqualTo ProjectionGenerationState.ACTIVE
            registry.get("voucher_projection_batch_events").summary().count() shouldBeEqualTo 2L
            registry.get("voucher_rebuild_progress_ratio").gauge().value() shouldBeEqualTo 1.0
            val cancelled = requestCancellation(rebuilds)
            awaitCancelled(cancelled)
        } finally {
            runtime.stopProjection()
            runtime.stopRebuild()
            runtime.stopMaintenance()
            runtime.releaseFencedLeases()
        }
    }

    @Test
    fun `runtime degrades projection health on a failed tick and recovers after a successful retry`() {
        appendEvents()
        val leases = ProjectionLeaseRepository()
        val rebuilds = ProjectionRebuildRepository()
        val projections = ProjectionRepository(leases)
        val operationalState = EventSourcedOperationalState()
        val reader = RecoverableProjectionEventReader()
        val runtime =
            runtime(
                leases,
                rebuilds,
                projections,
                ProjectionRuntimeTelemetry(operationalState, EventSourcedMetrics(SimpleMeterRegistry())),
                reader,
            )

        try {
            runtime.start()
            check(reader.failureObserved.await(5, TimeUnit.SECONDS)) { "runtime failure was not observed" }
            await().atMost(RUNTIME_TIMEOUT).untilAsserted {
                operationalState.isProjectionDegraded() shouldBeEqualTo true
            }

            reader.allowRecovery.countDown()
            awaitActiveCheckpoint(projections, INITIAL_TARGET_POSITION)
            await().atMost(RUNTIME_TIMEOUT).untilAsserted {
                operationalState.isProjectionDegraded() shouldBeEqualTo false
            }
        } finally {
            reader.allowRecovery.countDown()
            runtime.stopProjection()
            runtime.stopRebuild()
            runtime.stopMaintenance()
            runtime.releaseFencedLeases()
        }
    }

    private fun appendThirdEvent() {
        val events = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        transaction(database) {
            events.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId("tenant-a"), "voucher", STREAM_ID),
                        expectedVersion = INITIAL_TARGET_POSITION,
                        events = listOf(event(THIRD_EVENT_ID, "voucher.expired")),
                    ),
                ),
            )
        }
    }

    private fun appendEvents() {
        val events = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
        transaction(database) {
            events.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId("tenant-a"), "voucher", STREAM_ID),
                        expectedVersion = 0,
                        events =
                            listOf(
                                event(FIRST_EVENT_ID, "voucher.allocated"),
                                event(SECOND_EVENT_ID, "voucher.redeemed"),
                            ),
                    ),
                ),
            )
        }
    }

    private fun runtime(
        leases: ProjectionLeaseRepository,
        rebuilds: ProjectionRebuildRepository,
        projections: ProjectionRepository,
        telemetry: ProjectionRuntimeTelemetry,
        eventReader: ProjectionEventReader = ExposedProjectionEventReader(),
    ): EventSourcedProjectionRuntime =
        EventSourcedProjectionRuntime(
            resources =
                ProjectionRuntimeResources(
                    database = database,
                    permits = EventSourcedDatabasePermitGate(),
                    leases = leases,
                    projections = projections,
                    rebuilds = rebuilds,
                    eventReader = eventReader,
                ),
            properties = ProjectionWorkerProperties(enabled = true),
            clock = Clock.systemUTC(),
            telemetry = telemetry,
        )

    private fun awaitActiveCheckpoint(
        projections: ProjectionRepository,
        expectedPosition: Long,
    ) {
        await().atMost(RUNTIME_TIMEOUT).untilAsserted {
            transaction(database) {
                val active = checkNotNull(findActive(VOUCHER_LIFECYCLE_PROJECTION))
                projections.checkpoint(ProjectionKey(VOUCHER_LIFECYCLE_PROJECTION, active.generation))
                    ?.position
            } shouldBeEqualTo expectedPosition
        }
    }

    private fun awaitActivated(candidate: ProjectionGeneration) {
        await().atMost(RUNTIME_TIMEOUT).untilAsserted {
            transaction(database) { findActive(VOUCHER_LIFECYCLE_PROJECTION)?.generation } shouldBeEqualTo
                candidate.key.generation
            transaction(database) { findGeneration(candidate.key)?.state } shouldBeEqualTo
                ProjectionGenerationState.ACTIVE
        }
    }

    private fun requestCancellation(rebuilds: ProjectionRebuildRepository): ProjectionKey =
        transaction(database) {
            val generation =
                rebuilds.start(VOUCHER_LIFECYCLE_PROJECTION, FINAL_TARGET_POSITION, Clock.systemUTC().instant())
            rebuilds.requestCancellation(generation.key, Clock.systemUTC().instant())
            generation.key
        }

    private fun awaitCancelled(cancelled: ProjectionKey) {
        await().atMost(RUNTIME_TIMEOUT).untilAsserted {
            transaction(database) { findGeneration(cancelled)?.state } shouldBeEqualTo
                ProjectionGenerationState.CANCELLED
        }
    }

    private fun event(
        eventId: String,
        eventType: String,
    ): EventToAppend =
        EventToAppend(
            eventId = UUID.fromString(eventId),
            eventType = eventType,
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )

    private companion object {
        private const val INITIAL_TARGET_POSITION = 2L
        private const val FINAL_TARGET_POSITION = 3L
        private const val FIRST_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc401"
        private const val SECOND_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc402"
        private const val THIRD_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc403"
        private val STREAM_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc400")
        private val RUNTIME_TIMEOUT: Duration = Duration.ofSeconds(10)
        private val TABLES =
            arrayOf(
                EventLog,
                StreamHeads,
                AppendFences,
                ProjectionLeases,
                ProjectionProcessedEvents,
                ProjectionReadModels,
                CampaignProjectionReadModels,
                ProjectionCheckpoints,
                ProjectionPoisonEvents,
                ProjectionGenerations,
                ActiveProjectionGenerations,
            )
    }
}

private class RecoverableProjectionEventReader : ProjectionEventReader {
    val failureObserved = CountDownLatch(1)
    val allowRecovery = CountDownLatch(1)
    private val first = AtomicBoolean(true)
    private val delegate = ExposedProjectionEventReader()

    override fun loadAfter(globalPosition: Long): CommittedProjectionBatch {
        if (first.compareAndSet(true, false)) {
            failureObserved.countDown()
            error("synthetic projection reader failure")
        }
        check(allowRecovery.await(5, TimeUnit.SECONDS)) { "projection recovery was not released" }
        return delegate.loadAfter(globalPosition)
    }
}

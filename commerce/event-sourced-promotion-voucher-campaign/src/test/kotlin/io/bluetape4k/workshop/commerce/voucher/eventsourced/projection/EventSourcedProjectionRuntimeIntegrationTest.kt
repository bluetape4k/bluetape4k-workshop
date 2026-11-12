package io.bluetape4k.workshop.commerce.voucher.eventsourced.projection

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.logging.KLogging
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedProjectionRuntimeIntegrationTest {

    private companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

        private const val INITIAL_TARGET_POSITION = 2L
        private const val FINAL_TARGET_POSITION = 3L
        private const val FIRST_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc401"
        private const val SECOND_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc402"
        private const val THIRD_EVENT_ID = "0198a1b2-c3d4-7e5f-8123-456789abc403"
        private val STREAM_ID = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc400")
        private val RUNTIME_TIMEOUT: kotlin.time.Duration = 10.seconds
        private val RUNTIME_INTERVAL: kotlin.time.Duration = 100.milliseconds
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


    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-projection-runtime")
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) { SchemaUtils.create(*TABLES) }
    }

    @AfterEach
    fun dropSchema() {
        transaction(database) { SchemaUtils.drop(*TABLES) }
    }

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

            val candidate = transaction(database) {
                rebuilds.start(VOUCHER_LIFECYCLE_PROJECTION, INITIAL_TARGET_POSITION, Clock.systemUTC().instant())
            }
            awaitActivated(candidate)
            transaction(database) {
                findGeneration(candidate.key)?.targetPosition
            } shouldBeEqualTo FINAL_TARGET_POSITION

            operationalState.isProjectionDegraded().shouldBeFalse()
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
            reader.failureObserved.await(5.seconds).shouldBeTrue()
            await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
                operationalState.isProjectionDegraded().shouldBeTrue()
            }

            reader.allowRecovery.countDown()
            awaitActiveCheckpoint(projections, INITIAL_TARGET_POSITION)
            await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
                operationalState.isProjectionDegraded().shouldBeFalse()
            }
        } finally {
            reader.allowRecovery.countDown()
            runtime.stopProjection()
            runtime.stopRebuild()
            runtime.stopMaintenance()
            runtime.releaseFencedLeases()
        }
    }

    @Test
    fun `runtime keeps rebuild poison degraded without inflating the failed poison gauge`() {
        appendEvents()
        val leases = ProjectionLeaseRepository()
        val rebuilds = ProjectionRebuildRepository()
        val projections = ProjectionRepository(leases)
        val operationalState = EventSourcedOperationalState()
        val registry = SimpleMeterRegistry()

        val runtime = runtimeWithRebuildHandler(
            leases = leases,
            rebuilds = rebuilds,
            projections = projections,
            telemetry = ProjectionRuntimeTelemetry(operationalState, EventSourcedMetrics(registry)),
            rebuildHandler = {
                throw ProjectionPoisonException("HANDLER_REJECTED")
            },
        )

        try {
            runtime.start()
            awaitActiveCheckpoint(projections, INITIAL_TARGET_POSITION)
            val candidate =
                transaction(database) {
                    rebuilds.start(VOUCHER_LIFECYCLE_PROJECTION, INITIAL_TARGET_POSITION, Clock.systemUTC().instant())
                }

            await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
                operationalState.isProjectionDegraded().shouldBeTrue()
                transaction(database) {
                    findGeneration(candidate.key)?.state
                } shouldBeEqualTo ProjectionGenerationState.BUILDING
                registry.get("voucher_projection_poison_events")
                    .tag("reasonClass", "HANDLER_REJECTED")
                    .gauge()
                    .value() shouldBeEqualTo 1.0
            }
            await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
                registry.get("voucher_projection_poison_events")
                    .tag("reasonClass", "HANDLER_REJECTED")
                    .gauge()
                    .value() shouldBeEqualTo 1.0
            }
        } finally {
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
            resources = ProjectionRuntimeResources(
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

    private fun runtimeWithRebuildHandler(
        leases: ProjectionLeaseRepository,
        rebuilds: ProjectionRebuildRepository,
        projections: ProjectionRepository,
        telemetry: ProjectionRuntimeTelemetry,
        rebuildHandler: ProjectionEventHandler,
    ): EventSourcedProjectionRuntime =
        EventSourcedProjectionRuntime(
            resources = ProjectionRuntimeResources(
                database = database,
                permits = EventSourcedDatabasePermitGate(),
                leases = leases,
                projections = projections,
                rebuilds = rebuilds,
                rebuildHandler = rebuildHandler,
            ),
            properties = ProjectionWorkerProperties(enabled = true),
            clock = Clock.systemUTC(),
            telemetry = telemetry,
        )

    private fun awaitActiveCheckpoint(
        projections: ProjectionRepository,
        expectedPosition: Long,
    ) {
        await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
            transaction(database) {
                val active = findActive(VOUCHER_LIFECYCLE_PROJECTION).shouldNotBeNull()
                projections
                    .checkpoint(ProjectionKey(VOUCHER_LIFECYCLE_PROJECTION, active.generation))
                    ?.position
            } shouldBeEqualTo expectedPosition
        }
    }

    private fun awaitActivated(candidate: ProjectionGeneration) {
        await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
            transaction(database) {
                findActive(VOUCHER_LIFECYCLE_PROJECTION)?.generation
            } shouldBeEqualTo candidate.key.generation

            transaction(database) {
                findGeneration(candidate.key)?.state
            } shouldBeEqualTo ProjectionGenerationState.ACTIVE
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
        await atMost RUNTIME_TIMEOUT withPollInterval RUNTIME_INTERVAL untilAsserted {
            transaction(database) {
                findGeneration(cancelled)?.state
            } shouldBeEqualTo ProjectionGenerationState.CANCELLED
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
}

private class RecoverableProjectionEventReader: ProjectionEventReader {
    val failureObserved = CountDownLatch(1)
    val allowRecovery = CountDownLatch(1)
    private val first = AtomicBoolean(true)
    private val delegate = ExposedProjectionEventReader()

    override fun loadAfter(globalPosition: Long): CommittedProjectionBatch {
        if (first.compareAndSet(true, false)) {
            failureObserved.countDown()
            error("synthetic projection reader failure")
        }
        check(allowRecovery.await(5.seconds)) { "projection recovery was not released" }
        return delegate.loadAfter(globalPosition)
    }
}

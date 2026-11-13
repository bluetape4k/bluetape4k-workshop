package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.millis
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.DatabaseBulkheadRejected
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitBudget
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedDatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventStoreAppendIntegrationTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    }

    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database
    private lateinit var store: EventStoreRepository

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase = EventSourcedPostgresTestDatabase(postgres, "issue-538-event-store")
        database = postgresDatabase.database
        store = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
    }

    @AfterAll
    fun closeDataSource() {
        postgresDatabase.close()
    }

    @BeforeEach
    fun createSchema() {
        transaction(database) {
            SchemaUtils.drop(EventLog, StreamHeads, AppendFences)
            SchemaUtils.create(EventLog, StreamHeads, AppendFences)
        }
    }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(EventLog, StreamHeads, AppendFences) }

    @Test
    fun `append assigns contiguous stream and global positions then load returns the committed tail`() {
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())

        val result =
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(
                            stream = stream,
                            expectedVersion = 0,
                            events = listOf(
                                event("0198a1b2-c3d4-7e5f-8123-456789abc001"),
                                event("0198a1b2-c3d4-7e5f-8123-456789abc002"),
                            ),
                        ),
                    ),
                )
            }

        result.shouldBeInstanceOf<AppendResult.Appended>()

        log.debug { "result: $result" }
        result.finalVersions[stream] shouldBeEqualTo 2L
        result.firstGlobalPosition shouldBeEqualTo 1L
        result.lastGlobalPosition shouldBeEqualTo 2L

        val page = store.load(EventStoreRead(stream, afterVersion = 0))

        log.debug { "page: $page" }
        page.committedHead shouldBeEqualTo 2L
        page.events.map { it.stream.version } shouldBeEqualTo listOf(1L, 2L)
        page.events.map { it.globalPosition } shouldBeEqualTo listOf(1L, 2L)
    }

    @Test
    fun `expected version conflict leaves every requested stream unchanged`() {
        val campaign = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val voucher = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID())

        transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = campaign,
                        expectedVersion = 0,
                        events = listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc010"))
                    )
                )
            )
        }

        val result = transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(campaign, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc011"))),
                    ExpectedAppend(voucher, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc012"))),
                ),
            )
        }

        result.shouldBeInstanceOf<AppendResult.Conflict>()
        log.debug { "result: $result" }
        result.stream shouldBeEqualTo campaign
        result.expectedVersion shouldBeEqualTo 0L
        result.actualVersion shouldBeEqualTo 1L

        store.load(EventStoreRead(voucher, afterVersion = 0)).events.shouldBeEmpty()
    }

    @Test
    fun `rolled back append does not leave a global position gap`() {
        val abandoned = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())

        assertFailsWith<IllegalStateException> {
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(
                            stream = abandoned,
                            expectedVersion = 0,
                            events = listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc020"))
                        ),
                    ),
                )
                error("rollback append")
            }
        }

        val committed = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val result = transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = committed,
                        expectedVersion = 0,
                        events = listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc021"))
                    ),
                ),
            )
        }

        result.shouldBeInstanceOf<AppendResult.Appended>()
        result.firstGlobalPosition shouldBeEqualTo 1L
    }

    @Test
    fun `same expected version race commits exactly one event`() {
        val stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<AppendResult>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                barrier.await(5.seconds)
                val result = transaction(database) {
                    store.appendAll(
                        listOf(
                            ExpectedAppend(
                                stream = stream,
                                expectedVersion = 0,
                                events = listOf(event(nextV7EventId())),
                            ),
                        ),
                    )
                }
                log.debug { "result: $result" }
                results.add(result)
            }.run()

        results.count { it is AppendResult.Appended } shouldBeEqualTo 1
        results.count { it is AppendResult.Conflict } shouldBeEqualTo 1
        store.load(EventStoreRead(stream, afterVersion = 0)).events.map { it.stream.version } shouldBeEqualTo listOf(1L)
    }

    @Test
    fun `duplicate event identifier is returned as a domain result`() {
        val eventId = "0198a1b2-c3d4-7e5f-8123-456789abc030"

        transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                        expectedVersion = 0,
                        events = listOf(event(eventId)),
                    ),
                ),
            )
        }

        val result = transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(
                        stream = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID()),
                        expectedVersion = 0,
                        events = listOf(event(eventId)),
                    ),
                ),
            )
        }

        result.shouldBeInstanceOf<AppendResult.DuplicateEvent>()
        result.eventId shouldBeEqualTo UUID.fromString(eventId)
    }

    @Test
    fun `simultaneous duplicate event identifiers return a domain result without a SQL failure`() {
        val eventId = "0198a1b2-c3d4-7e5f-8123-456789abc031"
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<AppendResult>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                barrier.await(5.seconds)
                val result = transaction(database) {
                    store.appendAll(
                        listOf(
                            ExpectedAppend(
                                StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                                expectedVersion = 0,
                                events = listOf(event(eventId)),
                            ),
                        ),
                    )
                }
                log.debug { "result: $result" }
                results.add(result)
            }.run()

        results.count { it is AppendResult.Appended } shouldBeEqualTo 1
        results.count { it is AppendResult.DuplicateEvent } shouldBeEqualTo 1
    }

    @Test
    fun `multi stream append commits every stream in one contiguous range`() {
        val campaign = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val voucher = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID())

        val result = transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(campaign, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc040"))),
                    ExpectedAppend(voucher, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc041"))),
                ),
            )
        }

        log.debug { "result: $result" }
        result.shouldBeInstanceOf<AppendResult.Appended>()
        result.firstGlobalPosition shouldBeEqualTo 1L
        result.lastGlobalPosition shouldBeEqualTo 2L

        store.load(EventStoreRead(campaign, afterVersion = 0)).committedHead shouldBeEqualTo 1L
        store.load(EventStoreRead(voucher, afterVersion = 0)).committedHead shouldBeEqualTo 1L
    }

    @Test
    fun `same client UUID remains isolated across tenant and stream type`() {
        val sharedId = UUID.randomUUID()
        val tenantCampaign = StreamKey(TenantId("tenant-a"), "campaign", sharedId)
        val otherTenantCampaign = StreamKey(TenantId("tenant-b"), "campaign", sharedId)
        val tenantVoucher = StreamKey(TenantId("tenant-a"), "voucher", sharedId)

        val result = transaction(database) {
            store.appendAll(
                listOf(
                    ExpectedAppend(
                        tenantCampaign,
                        0,
                        listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc042")),
                    ),
                    ExpectedAppend(
                        otherTenantCampaign,
                        0,
                        listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc043")),
                    ),
                    ExpectedAppend(
                        tenantVoucher,
                        0,
                        listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc044")),
                    ),
                ),
            )
        }

        log.debug { "result: $result" }
        result.shouldBeInstanceOf<AppendResult.Appended>()

        listOf(tenantCampaign, otherTenantCampaign, tenantVoucher).forEach { stream ->
            store.load(EventStoreRead(stream, afterVersion = 0)).committedHead shouldBeEqualTo 1L
        }
    }

    @Test
    fun `foreground event reads reject before waiting on Hikari when their permit is occupied`() {
        val permits = EventSourcedDatabasePermitGate(
            budget =
                EventSourcedDatabasePermitBudget(
                    foreground = 1,
                    projection = 16,
                    rebuild = 1,
                    maintenance = 1,
                    readiness = 1,
                ),
            acquireTimeout = 25.millis(),
        )
        val runner = PermittedEventStoreTransactionRunner(database, permits)
        val permittedStore = EventStoreRepository(runner)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        val holding = executor.submit {
            runner.readTransaction {
                entered.countDown()
                release.await(5.seconds)
            }
        }

        try {
            entered.await(5.seconds).shouldBeTrue()
            assertFailsWith<DatabaseBulkheadRejected> {
                permittedStore.load(
                    EventStoreRead(
                        StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                        afterVersion = 0,
                    ),
                )
            }
        } finally {
            release.countDown()
            holding.get(5.seconds)
            executor.shutdownNow()
        }
    }

    @Test
    fun `thirty two independent streams receive unique contiguous positions`() {
        val streams = ConcurrentLinkedQueue(List(32) {
            StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        })
        val results = ConcurrentLinkedQueue<AppendResult>()

        MultithreadingTester()
            .workers(streams.size)
            .rounds(1)
            .add {
                streams.poll()?.let { stream ->
                    results.add(
                        transaction(database) {
                            store.appendAll(
                                listOf(
                                    ExpectedAppend(stream, 0, listOf(event(nextV7EventId()))),
                                ),
                            )
                        },
                    )
                }
            }.run()

        results.count { it is AppendResult.Appended } shouldBeEqualTo 32

        val positions = results
            .filterIsInstance<AppendResult.Appended>()
            .map(AppendResult.Appended::firstGlobalPosition)
            .sorted()
        positions shouldBeEqualTo (1L..32L).toList()
    }

    private fun event(eventId: String): EventToAppend =
        EventToAppend(
            eventId = UUID.fromString(eventId),
            eventType = "campaign.created",
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )

    private fun nextV7EventId(): String =
        "0198a1b2-c3d4-7e5f-8123-${UUID.randomUUID().toString().substringAfterLast('-')}"
}

package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import io.bluetape4k.junit5.concurrency.MultithreadingTester

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventStoreAppendIntegrationTest {
    private val postgres = PostgreSQLServer.Launcher.postgres
    private lateinit var database: Database
    private lateinit var store: EventStoreRepository

    @BeforeAll
    fun connectPostgres() {
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = requireNotNull(postgres.username),
                password = requireNotNull(postgres.password),
            )
        store = EventStoreRepository(ExposedEventStoreTransactionRunner(database))
    }

    @BeforeEach
    fun createSchema() =
        transaction(database) {
            SchemaUtils.drop(EventLog, StreamHeads, AppendFences)
            SchemaUtils.create(EventLog, StreamHeads, AppendFences)
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
                            stream,
                            expectedVersion = 0,
                            events =
                                listOf(
                                    event("0198a1b2-c3d4-7e5f-8123-456789abc001"),
                                    event("0198a1b2-c3d4-7e5f-8123-456789abc002"),
                                ),
                        ),
                    ),
                )
            }

        (result is AppendResult.Appended).shouldBeTrue()
        val appended = result as AppendResult.Appended
        appended.finalVersions[stream] shouldBeEqualTo 2L
        appended.firstGlobalPosition shouldBeEqualTo 1L
        appended.lastGlobalPosition shouldBeEqualTo 2L

        val page = store.load(EventStoreRead(stream, afterVersion = 0))

        page.committedHead shouldBeEqualTo 2L
        page.events.map { it.stream.version } shouldBeEqualTo listOf(1L, 2L)
        page.events.map { it.globalPosition } shouldBeEqualTo listOf(1L, 2L)
    }

    @Test
    fun `expected version conflict leaves every requested stream unchanged`() {
        val campaign = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val voucher = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID())
        transaction(database) {
            store.appendAll(listOf(ExpectedAppend(campaign, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc010")))))
        }

        val result =
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(campaign, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc011"))),
                        ExpectedAppend(voucher, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc012"))),
                    ),
                )
            }

        (result is AppendResult.Conflict).shouldBeTrue()
        val conflict = result as AppendResult.Conflict
        conflict.stream shouldBeEqualTo campaign
        conflict.expectedVersion shouldBeEqualTo 0L
        conflict.actualVersion shouldBeEqualTo 1L
        store.load(EventStoreRead(voucher, afterVersion = 0)).events shouldBeEqualTo emptyList()
    }

    @Test
    fun `rolled back append does not leave a global position gap`() {
        val abandoned = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        assertFailsWith<IllegalStateException> {
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(abandoned, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc020"))),
                    ),
                )
                error("rollback append")
            }
        }

        val committed = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val result =
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(committed, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc021"))),
                    ),
                )
            }

        (result is AppendResult.Appended).shouldBeTrue()
        (result as AppendResult.Appended).firstGlobalPosition shouldBeEqualTo 1L
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
                barrier.await(5, TimeUnit.SECONDS)
                val result =
                    transaction(database) {
                        store.appendAll(
                            listOf(
                                ExpectedAppend(
                                    stream,
                                    expectedVersion = 0,
                                    events = listOf(event(nextV7EventId())),
                                ),
                            ),
                        )
                    }
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
                        StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                        expectedVersion = 0,
                        events = listOf(event(eventId)),
                    ),
                ),
            )
        }

        val result =
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(
                            StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID()),
                            expectedVersion = 0,
                            events = listOf(event(eventId)),
                        ),
                    ),
                )
            }

        (result is AppendResult.DuplicateEvent).shouldBeTrue()
        (result as AppendResult.DuplicateEvent).eventId shouldBeEqualTo UUID.fromString(eventId)
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
                barrier.await(5, TimeUnit.SECONDS)
                results.add(
                    transaction(database) {
                        store.appendAll(
                            listOf(
                                ExpectedAppend(
                                    StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()),
                                    expectedVersion = 0,
                                    events = listOf(event(eventId)),
                                ),
                            ),
                        )
                    },
                )
            }.run()

        results.count { it is AppendResult.Appended } shouldBeEqualTo 1
        results.count { it is AppendResult.DuplicateEvent } shouldBeEqualTo 1
    }

    @Test
    fun `multi stream append commits every stream in one contiguous range`() {
        val campaign = StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID())
        val voucher = StreamKey(TenantId("tenant-a"), "voucher", UUID.randomUUID())

        val result =
            transaction(database) {
                store.appendAll(
                    listOf(
                        ExpectedAppend(campaign, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc040"))),
                        ExpectedAppend(voucher, 0, listOf(event("0198a1b2-c3d4-7e5f-8123-456789abc041"))),
                    ),
                )
            }

        (result is AppendResult.Appended).shouldBeTrue()
        val appended = result as AppendResult.Appended
        appended.firstGlobalPosition shouldBeEqualTo 1L
        appended.lastGlobalPosition shouldBeEqualTo 2L
        store.load(EventStoreRead(campaign, afterVersion = 0)).committedHead shouldBeEqualTo 1L
        store.load(EventStoreRead(voucher, afterVersion = 0)).committedHead shouldBeEqualTo 1L
    }

    @Test
    fun `thirty two independent streams receive unique contiguous positions`() {
        val streams = ConcurrentLinkedQueue(List(32) { StreamKey(TenantId("tenant-a"), "campaign", UUID.randomUUID()) })
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
        val positions =
            results
                .filterIsInstance<AppendResult.Appended>()
                .map(AppendResult.Appended::firstGlobalPosition)
                .sorted()
        positions shouldBeEqualTo (1L..32L).toList()
    }

    private fun event(eventId: String) =
        EventToAppend(
            eventId = UUID.fromString(eventId),
            eventType = "campaign.created",
            schemaVersion = 1,
            payload = EventPayload("{}"),
        )

    private fun nextV7EventId(): String =
        "0198a1b2-c3d4-7e5f-8123-${UUID.randomUUID().toString().substringAfterLast('-')}"
}

package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.support.EventSourcedPostgresTestDatabase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration

@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class EventSourcedAppendStressTest {
    private lateinit var postgresDatabase: EventSourcedPostgresTestDatabase
    private lateinit var database: Database
    private lateinit var metrics: StressAppendMetrics
    private lateinit var store: EventStoreRepository

    @BeforeAll
    fun connectPostgres() {
        postgresDatabase =
            EventSourcedPostgresTestDatabase(
                PostgreSQLServer.Launcher.postgres,
                "issue-538-append-stress",
                maximumPoolSize = HIKARI_POOL_SIZE,
            )
        database = postgresDatabase.database
    }

    @AfterAll
    fun closeDataSource() = postgresDatabase.close()

    @BeforeEach
    fun createSchema() {
        transaction(database) {
            SchemaUtils.drop(EventLog, StreamHeads, AppendFences)
            SchemaUtils.create(EventLog, StreamHeads, AppendFences)
        }
        metrics = StressAppendMetrics()
        store = EventStoreRepository(ExposedEventStoreTransactionRunner(database), metrics)
    }

    @AfterEach
    fun dropSchema() = transaction(database) { SchemaUtils.drop(EventLog, StreamHeads, AppendFences) }

    @Test
    fun `hot campaign converges one thousand commands with bounded retry`() {
        val result = runProfile(StressProfile.hot())

        result.assertCorrectness()
        if (dedicatedProfile()) {
            result.committedThroughput shouldBeGreaterOrEqualTo HOT_MIN_THROUGHPUT
            result.successRatio shouldBeGreaterOrEqualTo MIN_SUCCESS_RATIO
            result.p95 shouldBeLessOrEqualTo(Duration.ofSeconds(2))
            result.p99 shouldBeLessOrEqualTo(Duration.ofSeconds(5))
            result.timeoutRatio shouldBeLessOrEqualTo(MAX_TIMEOUT_RATIO)
        }
    }

    @Test
    fun `independent campaign streams preserve global fencing throughput`() {
        val result = runProfile(StressProfile.independent())

        result.assertCorrectness()
        if (dedicatedProfile()) {
            result.committedThroughput shouldBeGreaterOrEqualTo INDEPENDENT_MIN_THROUGHPUT
            metrics.appendFencePercentile(0.95) shouldBeLessOrEqualTo Duration.ofMillis(100)
            metrics.appendFencePercentile(0.99) shouldBeLessOrEqualTo Duration.ofMillis(500)
            result.timeoutRatio shouldBeLessOrEqualTo(MAX_TIMEOUT_RATIO)
        }
    }

    @Test
    fun `same version collision is reported separately from committed throughput`() {
        val stream = StreamKey(TenantId(TENANT), CAMPAIGN_STREAM, UUID.randomUUID())
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
                            listOf(ExpectedAppend(stream, expectedVersion = 0, events = listOf(stressEvent(0)))),
                        )
                    },
                )
            }.run()

        results.count { it is AppendResult.Appended } shouldBeEqualTo 1
        results.count { it is AppendResult.Conflict } shouldBeEqualTo 1
    }

    private fun runProfile(profile: StressProfile): StressResult {
        val streams =
            List(profile.campaigns) {
                StreamKey(TenantId(TENANT), CAMPAIGN_STREAM, UUID.randomUUID())
            }
        val streamLocks = streams.associateWithTo(ConcurrentHashMap()) { ReentrantLock(true) }
        val terminalLatencies = ConcurrentLinkedQueue<Duration>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val committed = AtomicInteger()
        val conflicts = AtomicInteger()
        val timeouts = AtomicInteger()
        val maxHikariWaiting = AtomicInteger()
        val evidence =
            StressEvidence(
                committed,
                conflicts,
                timeouts,
                maxHikariWaiting,
                terminalLatencies,
                failures,
            )
        val started = TimeSource.Monotonic.markNow()

        VirtualThreads.executorService().use { executor ->
            val futures =
                List(profile.clients) { client ->
                    executor.submit {
                        for (commandIndex in client until profile.commands step profile.clients) {
                            val stream = streams[commandIndex % streams.size]
                            executeCommand(
                                stream = stream,
                                commandIndex = commandIndex,
                                streamLock = streamLocks.getValue(stream),
                                evidence = evidence,
                            )
                            maxHikariWaiting.accumulateAndGet(
                                postgresDatabase.dataSource.hikariPoolMXBean.threadsAwaitingConnection,
                                ::maxOf,
                            )
                        }
                    }
                }
            futures.forEach { it.get(COMMAND_DEADLINE.seconds, TimeUnit.SECONDS) }
        }

        failures.peek()?.let { throw AssertionError("stress command failed", it) }
        val result =
            StressResult(
                profile = profile,
                committed = committed.get(),
                conflicts = conflicts.get(),
                timeouts = timeouts.get(),
                elapsed = started.elapsedNow().toJavaDuration(),
                terminalLatencies = terminalLatencies.toList(),
                maxHikariWaiting = maxHikariWaiting.get(),
            )
        println(result.evidence(metrics))
        return result
    }

    private fun executeCommand(
        stream: StreamKey,
        commandIndex: Int,
        streamLock: ReentrantLock,
        evidence: StressEvidence,
    ) {
        val started = TimeSource.Monotonic.markNow()
        try {
            streamLock.withLock {
                appendWithRetry(stream, commandIndex, evidence.conflicts)
            }
            evidence.committed.incrementAndGet()
        } catch (failure: SQLException) {
            if (failure.sqlState in TIMEOUT_SQL_STATES) {
                evidence.timeouts.incrementAndGet()
            } else {
                evidence.failures.add(failure)
            }
        } catch (failure: Throwable) {
            evidence.failures.add(failure)
        } finally {
            evidence.terminalLatencies += started.elapsedNow().toJavaDuration()
        }
    }

    private fun appendWithRetry(
        stream: StreamKey,
        commandIndex: Int,
        conflicts: AtomicInteger,
    ) {
        repeat(MAX_CONFLICT_RETRIES) {
            val expectedVersion = store.load(EventStoreRead(stream, afterVersion = 0)).committedHead
            val result =
                transaction(database) {
                    TransactionManager.current().exec("SET LOCAL lock_timeout = '500ms'")
                    TransactionManager.current().exec("SET LOCAL statement_timeout = '3s'")
                    store.appendAll(
                        listOf(
                            ExpectedAppend(
                                stream = stream,
                                expectedVersion = expectedVersion,
                                events = listOf(stressEvent(commandIndex)),
                            ),
                        ),
                    )
                }
            when (result) {
                is AppendResult.Appended -> return
                is AppendResult.Conflict -> conflicts.incrementAndGet()
                is AppendResult.DuplicateEvent -> error("stress event identifiers must be unique")
            }
        }
        error("command did not converge within $MAX_CONFLICT_RETRIES retries")
    }

    private fun StressResult.assertCorrectness() {
        committed + timeouts shouldBeEqualTo profile.commands
        committed shouldBeGreaterOrEqualTo((profile.commands * MIN_SUCCESS_RATIO).toInt())
        elapsed shouldBeLessOrEqualTo(COMMAND_DEADLINE)
        maxHikariWaiting shouldBeLessOrEqualTo(profile.clients)
        postgresDatabase.dataSource.hikariPoolMXBean.threadsAwaitingConnection shouldBeEqualTo 0
        metrics.streamHeadSamples().shouldBeEqualTo(committed + conflicts)
        metrics.appendFenceSamples().shouldBeEqualTo(committed)
        terminalLatencies.size shouldBeEqualTo profile.commands
        terminalLatencies.all { it <= COMMAND_DEADLINE }.shouldBeTrue()
    }

    private fun StressResult.evidence(metrics: StressAppendMetrics): String =
        "event-sourced-stress profile=${profile.name} terminalOps=${profile.commands} " +
            "committed=$committed conflicts409=$conflicts timeouts=$timeouts " +
            "terminalThroughput=${terminalThroughput.format()} committedThroughput=${committedThroughput.format()} " +
            "p95=${p95.toMillis()}ms p99=${p99.toMillis()}ms hikariWaitingMax=$maxHikariWaiting " +
            "streamHeadP95=${metrics.streamHeadPercentile(0.95).toMillis()}ms " +
            "appendFenceP95=${metrics.appendFencePercentile(0.95).toMillis()}ms"

    private fun Double.format(): String = "%.2f".format(Locale.ROOT, this)

    private companion object {
        private const val TENANT = "tenant-stress"
        private const val CAMPAIGN_STREAM = "campaign"
        private const val HIKARI_POOL_SIZE = 20
        private const val MAX_CONFLICT_RETRIES = 1_000
        private const val HOT_MIN_THROUGHPUT = 20.0
        private const val INDEPENDENT_MIN_THROUGHPUT = 40.0
        private const val MIN_SUCCESS_RATIO = 0.95
        private const val MAX_TIMEOUT_RATIO = 0.01
        private val COMMAND_DEADLINE = Duration.ofSeconds(60)
        private val TIMEOUT_SQL_STATES = setOf("55P03", "57014")

        private fun dedicatedProfile(): Boolean = System.getProperty("eventSourcedStress").toBoolean()
    }
}

private data class StressEvidence(
    val committed: AtomicInteger,
    val conflicts: AtomicInteger,
    val timeouts: AtomicInteger,
    val maxHikariWaiting: AtomicInteger,
    val terminalLatencies: ConcurrentLinkedQueue<Duration>,
    val failures: ConcurrentLinkedQueue<Throwable>,
)

@ConsistentCopyVisibility
private data class StressProfile private constructor(
    val name: String,
    val clients: Int,
    val campaigns: Int,
    val commands: Int,
) {
    companion object {
        fun hot(): StressProfile = create("hot", campaigns = 1)

        fun independent(): StressProfile = create("independent", campaigns = 32)

        private fun create(
            name: String,
            campaigns: Int,
        ): StressProfile =
            StressProfile(
                name = name.requireNotBlank("name"),
                clients = 64.requirePositiveNumber("clients"),
                campaigns = campaigns.requirePositiveNumber("campaigns"),
                commands = 1_000.requirePositiveNumber("commands"),
            )
    }
}

private data class StressResult(
    val profile: StressProfile,
    val committed: Int,
    val conflicts: Int,
    val timeouts: Int,
    val elapsed: Duration,
    val terminalLatencies: List<Duration>,
    val maxHikariWaiting: Int,
) {
    val terminalThroughput: Double = profile.commands / elapsed.toNanos().toDouble() * NANOS_PER_SECOND
    val committedThroughput: Double = committed / elapsed.toNanos().toDouble() * NANOS_PER_SECOND
    val successRatio: Double = committed.toDouble() / profile.commands
    val timeoutRatio: Double = timeouts.toDouble() / profile.commands
    val p95: Duration = terminalLatencies.percentile(0.95)
    val p99: Duration = terminalLatencies.percentile(0.99)

    private companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

private class StressAppendMetrics : EventStoreAppendMetrics {
    private val streamHeadWaits = ConcurrentLinkedQueue<Duration>()
    private val appendFenceWaits = ConcurrentLinkedQueue<Duration>()

    override fun streamHeadWait(duration: Duration) {
        streamHeadWaits += duration
    }

    override fun appendFenceWait(duration: Duration) {
        appendFenceWaits += duration
    }

    fun streamHeadSamples(): Int = streamHeadWaits.size

    fun appendFenceSamples(): Int = appendFenceWaits.size

    fun streamHeadPercentile(percentile: Double): Duration = streamHeadWaits.toList().percentile(percentile)

    fun appendFencePercentile(percentile: Double): Duration = appendFenceWaits.toList().percentile(percentile)
}

private fun stressEvent(commandIndex: Int): EventToAppend {
    val eventId = Uuid.V7.nextUUID()
    return EventToAppend(
        eventId = eventId,
        eventType = "stress.command-appended",
        schemaVersion = 1,
        payload = EventPayload("""{"commandIndex":$commandIndex}"""),
        occurredAt = Instant.now(),
        correlationId = eventId.toString(),
        actorSurrogate = "a".repeat(64),
        actorHmacKeyVersion = 1,
    )
}

private fun List<Duration>.percentile(percentile: Double): Duration {
    if (isEmpty()) return Duration.ZERO
    val validPercentile = percentile.coerceIn(0.0, 1.0)
    val sorted = map(Duration::toNanos).sorted()
    val index = (ceil(validPercentile * sorted.size).toInt() - 1).coerceIn(sorted.indices)
    return Duration.ofNanos(sorted[index])
}

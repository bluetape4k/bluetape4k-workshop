package io.bluetape4k.workshop.commerce.voucher.performance

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import java.lang.management.ManagementFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.math.ceil

internal data class VoucherStressEvidence(
    val jsonPath: Path,
    val jfrPath: Path,
    val hikariActiveMax: Int,
    val databasePermitMax: Int,
    val deterministicLockTimeoutContractPassed: Boolean,
    val resourceLeaks: Int,
    val capacityInvariant: Boolean,
)

/** Counts JDBC connection and statement execution boundaries without changing repository code. */
internal class VoucherJdbcProbeDataSource(
    private val delegate: DataSource,
) : DataSource by delegate {
    private val connectionAcquisitions = AtomicLong()
    private val statementExecutions = AtomicLong()

    override fun getConnection(): Connection = observed(delegate.connection)

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = observed(delegate.getConnection(username, password))

    fun connectionAcquisitions(): Long = connectionAcquisitions.get()

    fun statementExecutions(): Long = statementExecutions.get()

    private fun observed(connection: Connection): Connection {
        connectionAcquisitions.incrementAndGet()
        return proxy(connection, Connection::class.java) { method, result ->
            if (method.name in STATEMENT_FACTORIES && result is Statement) observed(result) else result
        }
    }

    private fun observed(statement: Statement): Statement =
        proxy(statement, statementContract(statement)) {
                method,
                result,
            ->
            if (method.name in EXECUTION_METHODS) statementExecutions.incrementAndGet()
            result
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(
        target: T,
        contract: Class<*>,
        afterInvocation: (Method, Any?) -> Any?,
    ): T =
        Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, arguments ->
            val result = invoke(target, method, arguments)
            afterInvocation(method, result)
        } as T

    private fun statementContract(statement: Statement): Class<out Statement> =
        when (statement) {
            is CallableStatement -> CallableStatement::class.java
            is PreparedStatement -> PreparedStatement::class.java
            else -> Statement::class.java
        }

    private fun invoke(
        target: Any,
        method: Method,
        arguments: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(target, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

    private companion object {
        val STATEMENT_FACTORIES = setOf("createStatement", "prepareStatement", "prepareCall")
        val EXECUTION_METHODS =
            setOf("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeBatch", "executeLargeUpdate")
    }
}

/**
 * Samples bounded pool, permit, PostgreSQL, Lettuce, allocation, and GC evidence for one profile.
 *
 * Latency values are deliberately report-only. Correctness gates use capacity, permit, leak, and
 * deterministic lock-timeout contracts instead of machine-dependent wall-clock thresholds.
 */
internal class VoucherPerformanceProbe(
    private val reportDirectory: Path,
    private val profile: String,
    private val concurrency: Int,
    private val redisMode: String,
    private val seed: Long,
    private val containerImage: String,
    private val hikari: HikariDataSource,
    private val databasePermits: DatabasePermitGate,
    private val jdbc: VoucherJdbcProbeDataSource,
    private val pgStatDataSource: DataSource,
    private val applicationName: String,
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val finished = AtomicBoolean()
    private val samples = ConcurrentLinkedQueue<OperationSample>()
    private val pgSamples = ConcurrentLinkedQueue<PgActivitySample>()
    private val hikariActiveMax = AtomicInteger()
    private val hikariIdleMax = AtomicInteger()
    private val hikariPendingMax = AtomicInteger()
    private val permitMax = AtomicInteger()
    private val laneActiveMax = DatabaseLane.entries.associateWith { AtomicInteger() }
    private val laneWaitMax = DatabaseLane.entries.associateWith { AtomicInteger() }
    private val redisCommands = AtomicLong()
    private val redisSucceeded = AtomicLong()
    private val redisFailed = AtomicLong()
    private val redisLatencyNanos = AtomicLong()
    private val startedAt = System.nanoTime()
    private val jfrPath = reportDirectory.resolve("$profile.jfr")
    private val jsonPath = reportDirectory.resolve("$profile.json")
    private val recording = createRecording()
    private val sampler =
        Thread.ofPlatform().name("voucher-stress-probe-$profile").start {
            while (running.get()) {
                sampleRuntime()
                try {
                    Thread.sleep(SAMPLE_INTERVAL)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

    init {
        Files.createDirectories(reportDirectory)
        recording.start()
    }

    fun recordOperation(
        operation: String,
        status: Int,
        latencyNanos: Long,
    ) {
        samples += OperationSample(operation, status, latencyNanos.coerceAtLeast(0))
    }

    fun recordRedisCommand(
        succeeded: Boolean,
        latencyNanos: Long,
    ) {
        redisCommands.incrementAndGet()
        if (succeeded) redisSucceeded.incrementAndGet() else redisFailed.incrementAndGet()
        redisLatencyNanos.addAndGet(latencyNanos.coerceAtLeast(0))
    }

    fun finish(
        expectedOperations: Map<String, Int>,
        capacityInvariant: Boolean,
        deterministicLockTimeoutContractPassed: Boolean,
    ): VoucherStressEvidence {
        check(finished.compareAndSet(false, true)) { "performance evidence was already finalized" }
        stopSampling()
        recording.stop()
        recording.dump(jfrPath)
        recording.close()

        val jfr = readJfr(jfrPath)
        val operationCounts = samples.groupingBy(OperationSample::operation).eachCount().toSortedMap()
        check(operationCounts == expectedOperations) {
            "operation matrix mismatch: expected=$expectedOperations actual=$operationCounts"
        }
        val statusCounts =
            (EXPECTED_STATUSES + samples.map { it.status })
                .associateWith { status -> samples.count { it.status == status } }
                .mapKeys { (status, _) -> status.toString() }
                .toSortedMap()
        val latencies = samples.map(OperationSample::latencyNanos).sorted()
        val elapsedNanos = (System.nanoTime() - startedAt).coerceAtLeast(1)
        val pool = hikari.hikariPoolMXBean
        val resourceLeaks = databasePermits.inUsePermits() + pool.activeConnections
        val maxConsecutiveWaitSamples = maxConsecutiveWaitSamples(pgSamples.toList())
        val evidence =
            VoucherStressEvidence(
                jsonPath = jsonPath,
                jfrPath = jfrPath,
                hikariActiveMax = hikariActiveMax.get(),
                databasePermitMax = permitMax.get(),
                deterministicLockTimeoutContractPassed = deterministicLockTimeoutContractPassed,
                resourceLeaks = resourceLeaks,
                capacityInvariant = capacityInvariant,
            )
        val document =
            linkedMapOf<String, Any>(
                "schemaVersion" to "1.0",
                "gitSha" to gitSha(),
                "environment" to
                    mapOf(
                        "javaVersion" to System.getProperty("java.version"),
                        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                        "cpuCount" to Runtime.getRuntime().availableProcessors(),
                        "containerImage" to containerImage,
                    ),
                "seed" to seed,
                "profile" to profile,
                "configuration" to
                    mapOf(
                        "concurrency" to concurrency,
                        "redisMode" to redisMode,
                        "hotspot" to true,
                        "sampleIntervalMillis" to SAMPLE_INTERVAL,
                    ),
                "operationCounts" to operationCounts,
                "statusCounts" to statusCounts,
                "latencyNanos" to
                    mapOf(
                        "p50" to percentile(latencies, 0.50),
                        "p95" to percentile(latencies, 0.95),
                        "p99" to percentile(latencies, 0.99),
                    ),
                "throughputPerSecond" to samples.size * TimeUnit.SECONDS.toNanos(1).toDouble() / elapsedNanos,
                "maxGauges" to
                    mapOf(
                        "hikariActive" to hikariActiveMax.get(),
                        "hikariIdle" to hikariIdleMax.get(),
                        "hikariPending" to hikariPendingMax.get(),
                        "databasePermits" to permitMax.get(),
                        "laneInUse" to laneActiveMax.mapKeys { it.key.name }.mapValues { it.value.get() },
                        "laneWaiting" to laneWaitMax.mapKeys { it.key.name }.mapValues { it.value.get() },
                    ),
                "roundTrips" to
                    mapOf(
                        "postgresTransactions" to jdbc.connectionAcquisitions(),
                        "postgresStatements" to jdbc.statementExecutions(),
                        "redisCommands" to redisCommands.get(),
                        "redisSucceeded" to redisSucceeded.get(),
                        "redisFailed" to redisFailed.get(),
                        "redisLatencyNanos" to redisLatencyNanos.get(),
                    ),
                "allocation" to
                    mapOf(
                        "sampledBytes" to jfr.allocationBytes,
                        "bytesPerOperation" to jfr.allocationBytes.toDouble() / samples.size.coerceAtLeast(1),
                    ),
                "gc" to mapOf("pauseCount" to jfr.gcPauseCount, "pauseNanos" to jfr.gcPauseNanos),
                "jfrEvents" to mapOf("threadPark" to jfr.threadParks, "monitorEnter" to jfr.monitorEnters),
                "pgStatActivity" to
                    mapOf(
                        "sampleCount" to pgSamples.size,
                        "maxConsecutiveWaitSamples" to maxConsecutiveWaitSamples,
                        "samples" to pgSamples.toList(),
                    ),
                "correctness" to
                    mapOf(
                        "capacityInvariant" to capacityInvariant,
                        "deterministicLockTimeoutContractPassed" to deterministicLockTimeoutContractPassed,
                        "resourceLeaks" to resourceLeaks,
                    ),
                "artifacts" to mapOf("json" to jsonPath.fileName.toString(), "jfr" to jfrPath.fileName.toString()),
                "probeSources" to
                    listOf(
                        "HikariPoolMXBean",
                        "DatabasePermitGate fair semaphore lane probes",
                        "VoucherJdbcProbeDataSource JDBC statement interceptor",
                        "Instrumented Lettuce-backed RateLimiter command boundary",
                        "PostgreSQL pg_stat_activity 10ms samples",
                        "JDK Flight Recorder allocation, park, monitor, and GC events",
                        "VoucherConcurrencyIntegrationTest deterministic SET LOCAL lock_timeout contract",
                    ),
            )
        Jackson.defaultJsonMapper.writeValue(jsonPath.toFile(), document)
        log.info {
            "voucher_stress_evidence_written profile=$profile operations=${samples.size} " +
                "hikariActiveMax=${evidence.hikariActiveMax} permitMax=${evidence.databasePermitMax}"
        }
        return evidence
    }

    override fun close() {
        if (finished.compareAndSet(false, true)) {
            stopSampling()
            runCatching { recording.stop() }
            recording.close()
        }
    }

    private fun sampleRuntime() {
        val pool = hikari.hikariPoolMXBean ?: return
        hikariActiveMax.accumulateAndGet(pool.activeConnections, ::maxOf)
        hikariIdleMax.accumulateAndGet(pool.idleConnections, ::maxOf)
        hikariPendingMax.accumulateAndGet(pool.threadsAwaitingConnection, ::maxOf)
        val laneActive = DatabaseLane.entries.sumOf(databasePermits::inUsePermits)
        permitMax.accumulateAndGet(laneActive, ::maxOf)
        DatabaseLane.entries.forEach { lane ->
            laneActiveMax.getValue(lane).accumulateAndGet(databasePermits.inUsePermits(lane), ::maxOf)
            laneWaitMax.getValue(lane).accumulateAndGet(databasePermits.waitingThreads(lane), ::maxOf)
        }
        samplePgActivity()
    }

    private fun samplePgActivity() {
        val waits = mutableListOf<String>()
        try {
            pgStatDataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT coalesce(wait_event_type, '') || ':' || coalesce(wait_event, '')
                      FROM pg_stat_activity
                     WHERE application_name = ?
                       AND pid <> pg_backend_pid()
                       AND state <> 'idle'
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, applicationName)
                    statement.executeQuery().use { result ->
                        while (result.next()) waits += result.getString(1)
                    }
                }
            }
        } catch (failure: Exception) {
            waits += "probe-error:${failure.javaClass.simpleName}"
        }
        pgSamples += PgActivitySample(Instant.now().toString(), waits.sorted())
    }

    private fun stopSampling() {
        running.set(false)
        sampler.interrupt()
        sampler.join(Duration.ofSeconds(5))
        sampleRuntime()
    }

    private fun createRecording(): Recording =
        Recording().apply {
            name = "voucher-stress-$profile"
            enable("jdk.ObjectAllocationSample")
            enable("jdk.ThreadPark").withThreshold(Duration.ZERO)
            enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO)
            enable("jdk.GarbageCollection")
            enable("jdk.GCPhasePause").withThreshold(Duration.ZERO)
        }

    private fun readJfr(path: Path): JfrEvidence {
        var allocationBytes = 0L
        var gcPauseCount = 0L
        var gcPauseNanos = 0L
        var threadParks = 0L
        var monitorEnters = 0L
        RecordingFile(path).use { recordingFile ->
            while (recordingFile.hasMoreEvents()) {
                val event = recordingFile.readEvent()
                when (event.eventType.name) {
                    "jdk.ObjectAllocationSample" -> allocationBytes += event.getLong("weight")
                    "jdk.GarbageCollection", "jdk.GCPhasePause" -> {
                        gcPauseCount++
                        gcPauseNanos += event.duration.toNanos()
                    }

                    "jdk.ThreadPark" -> threadParks++
                    "jdk.JavaMonitorEnter" -> monitorEnters++
                }
            }
        }
        return JfrEvidence(allocationBytes, gcPauseCount, gcPauseNanos, threadParks, monitorEnters)
    }

    private fun percentile(
        values: List<Long>,
        percentile: Double,
    ): Long {
        if (values.isEmpty()) return 0
        val index = (ceil(values.size * percentile).toInt() - 1).coerceIn(values.indices)
        return values[index]
    }

    private fun maxConsecutiveWaitSamples(samples: List<PgActivitySample>): Int {
        var current = 0
        var maximum = 0
        samples.forEach { sample ->
            current = if (sample.waits.any { it.isNotBlank() && !it.startsWith(":") }) current + 1 else 0
            maximum = maxOf(maximum, current)
        }
        return maximum
    }

    private fun gitSha(): String =
        runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
                .run {
                    check(waitFor(5, TimeUnit.SECONDS) && exitValue() == 0)
                    inputStream.bufferedReader().use { it.readText().trim() }
                }
        }.getOrElse { "unknown" }

    private data class OperationSample(val operation: String, val status: Int, val latencyNanos: Long)

    private data class PgActivitySample(val timestamp: String, val waits: List<String>)

    private data class JfrEvidence(
        val allocationBytes: Long,
        val gcPauseCount: Long,
        val gcPauseNanos: Long,
        val threadParks: Long,
        val monitorEnters: Long,
    )

    companion object : KLogging() {
        private const val SAMPLE_INTERVAL = 10L
        private val EXPECTED_STATUSES = listOf(200, 201, 409, 429, 503)
    }
}

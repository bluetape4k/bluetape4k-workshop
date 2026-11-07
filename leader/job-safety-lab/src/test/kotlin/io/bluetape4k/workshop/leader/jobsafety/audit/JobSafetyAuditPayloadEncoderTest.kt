package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer
import io.bluetape4k.leader.audit.http.HttpLeaderAuditExporter
import io.bluetape4k.leader.audit.http.LeaderAuditHttpOptions
import io.bluetape4k.leader.audit.http.LeaderAuditPayloadEncoder
import io.bluetape4k.leader.audit.http.LeaderAuditTrustedHttpsEndpoint
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

internal class JobSafetyAuditPayloadEncoderTest {

    companion object: KLogging()

    @Test
    fun `encoded history payload contains only bounded non-sensitive fields`() {
        val event = historyEvent()

        val body = JobSafetyAuditPayloadEncoder(64 * 1024)
            .encode(event)
            .body()
        val tree = Jackson.defaultJsonMapper.readTree(body)
        log.debug { "tree=$tree" }


        tree.toString().let {
            it shouldNotContain "redis-token-secret"
            it shouldNotContain "customer-42"
            it shouldNotContain "tenant-42"
            it shouldNotContain "raw stack detail"
            it shouldNotContain "lockName"
            it shouldNotContain "nodeId"
            it shouldNotContain "slotId"
            it shouldNotContain "errorMessage"
        }
        tree.path("occurredAt").asString() shouldBeEqualTo "2026-08-30T00:00:01Z"
        tree.path("kind").asString() shouldBeEqualTo "SINGLE"
        tree.path("status").asString() shouldBeEqualTo "FAILED"
        tree.path("durationMs").asLong() shouldBeEqualTo 42L
        tree.path("errorType").asString() shouldBeEqualTo "redacted"
        tree.path("attributes").path("redacted").asString() shouldBeEqualTo "redacted"
    }

    @Test
    fun `encoded lifecycle payload excludes lock and leader identities`() {
        val event = LeaderAuditExportEvent.Lifecycle.from(
            event = LeaderElectionEvent.Elected(
                lockName = "customer-42-secret-lock",
                leaderId = "node-secret",
                leaseExpiry = Instant.parse("2026-08-30T00:01:00Z"),
            ),
            attributes = linkedMapOf("tenantId" to "tenant-42", "safe" to "value"),
            sanitizer = LeaderAuditValueSanitizer.Default,
        )

        val body = JobSafetyAuditPayloadEncoder(64 * 1024).encode(event).body()
        val text = body.decodeToString()

        log.debug { "text=$text" }
        text shouldNotContain "customer-42"
        text shouldNotContain "node-secret"
        text shouldNotContain "tenant-42"
        text shouldNotContain "lockName"
        text shouldNotContain "leaderId"

        val tree = Jackson.defaultJsonMapper.readTree(body)
        tree.path("outcome").asString() shouldBeEqualTo "ELECTED"
        tree.path("leaseExpiry").asString() shouldBeEqualTo "2026-08-30T00:01:00Z"
    }

    @Test
    fun `encoder rejects invalid and oversized configured payload bounds`() {
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditPayloadEncoder(0)
        }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditPayloadEncoder(1024 * 1024 + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            JobSafetyAuditPayloadEncoder(1).encode(historyEvent())
        }
    }

    @Test
    fun `recording encoder captures immutable bytes for every transport`() {
        val store = BoundedAuditPayloadStore(maxEntries = 4, maxBytes = 64 * 1024)
        val delegate = JobSafetyAuditPayloadEncoder(64 * 1024)
        val encoder = RecordingLeaderAuditPayloadEncoder(delegate, store)
        val event = historyEvent()

        val returned = encoder.encode(event)
        val returnedBody = returned.body()
        returnedBody[0] = '{'.code.toByte()

        store.snapshot().single().decodeToString() shouldBeEqualTo delegate.encode(event).body().decodeToString()
        store.retainedBytes shouldBeEqualTo store.snapshot().single().size.toLong()
    }

    @Test
    fun `memory and https exporters share the same redacted payload boundary`() {
        val memoryStore = BoundedAuditPayloadStore(maxEntries = 4, maxBytes = 64 * 1024)
        val httpsStore = BoundedAuditPayloadStore(maxEntries = 4, maxBytes = 64 * 1024)
        val memoryClient = InMemoryAuditHttpClient()
        val httpsClient = InMemoryAuditHttpClient()
        val memoryExecutor = Executors.newSingleThreadExecutor()
        val httpsExecutor = Executors.newSingleThreadExecutor()
        val memoryScheduler = Executors.newSingleThreadScheduledExecutor()
        val httpsScheduler = Executors.newSingleThreadScheduledExecutor()
        val event = historyEvent()

        val memoryExporter = HttpLeaderAuditExporter(
            client = memoryClient,
            endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(URI("https://audit.example.test/memory")),
            headers = emptyMap(),
            encoder = recordingEncoder(memoryStore),
            exportOptions = exportOptions(memoryExecutor, memoryScheduler),
            httpOptions = LeaderAuditHttpOptions(maxPayloadBytes = 64 * 1024),
        )
        val httpsExporter = HttpLeaderAuditExporter(
            client = httpsClient,
            endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(URI("https://audit.example.test/https")),
            headers = emptyMap(),
            encoder = recordingEncoder(httpsStore),
            exportOptions = exportOptions(httpsExecutor, httpsScheduler),
            httpOptions = LeaderAuditHttpOptions(maxPayloadBytes = 64 * 1024),
        )

        try {
            memoryExporter.submit(event)
            httpsExporter.submit(event)
            memoryClient.awaitRequestCount(1, 2.seconds())
            httpsClient.awaitRequestCount(1, 2.seconds())

            await atMost 5.seconds untilAsserted {
                memoryExporter.snapshot().admitted shouldBeEqualTo 0
                httpsExporter.snapshot().admitted shouldBeEqualTo 0
            }

            val memoryBody = memoryStore.snapshot().single().decodeToString()
            val httpsBody = httpsStore.snapshot().single().decodeToString()

            log.debug { "memoryBody=$memoryBody" }
            memoryBody shouldBeEqualTo httpsBody
            memoryBody shouldNotContain "redis-token-secret"
            memoryBody shouldNotContain "customer-42"
            memoryStore.retainedBytes shouldBeEqualTo httpsStore.retainedBytes
        } finally {
            memoryExporter.close()
            httpsExporter.close()
            memoryClient.close()
            httpsClient.close()
            memoryScheduler.shutdownNow()
            httpsScheduler.shutdownNow()
            memoryExecutor.shutdownNow()
            httpsExecutor.shutdownNow()
        }
    }

    private fun recordingEncoder(store: BoundedAuditPayloadStore): LeaderAuditPayloadEncoder =
        RecordingLeaderAuditPayloadEncoder(JobSafetyAuditPayloadEncoder(64 * 1024), store)

    private fun exportOptions(
        executor: java.util.concurrent.Executor,
        scheduler: java.util.concurrent.ScheduledExecutorService,
    ): LeaderAuditExportOptions = LeaderAuditExportOptions(
        queueCapacity = 4,
        maxInFlight = 1,
        maxAttempts = 1,
        attemptTimeout = 1.seconds(),
        initialBackoff = 1.millis(),
        maxBackoff = 1.seconds(),
        executor = executor,
        scheduler = scheduler,
    )

    private fun historyEvent(): LeaderAuditExportEvent.History =
        LeaderAuditExportEvent.History.from(
            record = LeaderLockHistoryRecord(
                lockName = "customer-42-secret-job",
                token = "redis-token-secret",
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = Instant.parse("2026-08-30T00:00:00Z"),
                lockedUntil = Instant.parse("2026-08-30T00:01:00Z"),
                nodeId = "node-secret",
                finishedAt = Instant.parse("2026-08-30T00:00:01Z"),
                durationMs = 42L,
                status = LeaderHistoryStatus.FAILED,
                errorType = "java.lang.IllegalStateException",
                errorMessage = "customer-42 raw stack detail",
                slotId = "slot-secret",
                metadata = linkedMapOf("tenantId" to "tenant-42", "safe" to "value"),
            ),
            sanitizer = LeaderAuditValueSanitizer.Default,
        )
}

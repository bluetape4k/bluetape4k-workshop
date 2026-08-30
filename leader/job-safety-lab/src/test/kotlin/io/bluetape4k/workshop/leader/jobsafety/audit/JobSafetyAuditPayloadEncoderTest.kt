package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.leader.audit.http.HttpLeaderAuditExporter
import io.bluetape4k.leader.audit.http.LeaderAuditHttpOptions
import io.bluetape4k.leader.audit.http.LeaderAuditPayloadEncoder
import io.bluetape4k.leader.audit.http.LeaderAuditTrustedHttpsEndpoint
import org.junit.jupiter.api.Test
import java.time.Instant
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors

internal class JobSafetyAuditPayloadEncoderTest {

    @Test
    fun `encoded history payload contains only bounded non-sensitive fields`() {
        val event = historyEvent()

        val body = JobSafetyAuditPayloadEncoder(64 * 1024)
            .encode(event)
            .body()
        val tree = Jackson.defaultJsonMapper.readTree(body)

        tree.toString().contains("redis-token-secret").shouldBeFalse()
        tree.toString().contains("customer-42").shouldBeFalse()
        tree.toString().contains("tenant-42").shouldBeFalse()
        tree.toString().contains("raw stack detail").shouldBeFalse()
        tree.toString().contains("lockName").shouldBeFalse()
        tree.toString().contains("nodeId").shouldBeFalse()
        tree.toString().contains("slotId").shouldBeFalse()
        tree.toString().contains("errorMessage").shouldBeFalse()
        tree.path("occurredAt").asText() shouldBeEqualTo "2026-08-30T00:00:01Z"
        tree.path("kind").asText() shouldBeEqualTo "SINGLE"
        tree.path("status").asText() shouldBeEqualTo "FAILED"
        tree.path("durationMs").asLong() shouldBeEqualTo 42L
        tree.path("errorType").asText() shouldBeEqualTo "redacted"
        tree.path("attributes").path("redacted").asText() shouldBeEqualTo "redacted"
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
        val tree = Jackson.defaultJsonMapper.readTree(body)

        text.contains("customer-42").shouldBeFalse()
        text.contains("node-secret").shouldBeFalse()
        text.contains("tenant-42").shouldBeFalse()
        text.contains("lockName").shouldBeFalse()
        text.contains("leaderId").shouldBeFalse()
        tree.path("outcome").asText() shouldBeEqualTo "ELECTED"
        tree.path("leaseExpiry").asText() shouldBeEqualTo "2026-08-30T00:01:00Z"
    }

    @Test
    fun `encoder rejects invalid and oversized configured payload bounds`() {
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditPayloadEncoder(0) }
        assertFailsWith<IllegalArgumentException> { JobSafetyAuditPayloadEncoder(1024 * 1024 + 1) }
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

        store.snapshot().single().decodeToString() shouldBeEqualTo
            delegate.encode(event).body().decodeToString()
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
            memoryClient.awaitRequestCount(1, Duration.ofSeconds(2))
            httpsClient.awaitRequestCount(1, Duration.ofSeconds(2))

            val memoryBody = memoryStore.snapshot().single().decodeToString()
            val httpsBody = httpsStore.snapshot().single().decodeToString()
            memoryBody shouldBeEqualTo httpsBody
            memoryBody.contains("redis-token-secret").shouldBeFalse()
            memoryBody.contains("customer-42").shouldBeFalse()
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
        attemptTimeout = Duration.ofSeconds(1),
        initialBackoff = Duration.ofMillis(1),
        maxBackoff = Duration.ofSeconds(1),
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

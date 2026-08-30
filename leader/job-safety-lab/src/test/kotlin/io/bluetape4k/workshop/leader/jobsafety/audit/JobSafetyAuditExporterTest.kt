package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.leader.audit.LeaderAuditValueSanitizer
import io.bluetape4k.leader.audit.http.HttpLeaderAuditExporter
import io.bluetape4k.leader.audit.http.LeaderAuditHttpPayload
import io.bluetape4k.leader.audit.http.LeaderAuditTrustedHttpsEndpoint
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.time.Duration.Companion.seconds

internal class JobSafetyAuditExporterTest {

    private val schedulers = mutableListOf<ScheduledExecutorService>()

    @AfterEach
    fun tearDown() {
        schedulers.forEach { it.shutdownNow() }
    }

    @Test
    fun `memory exporter classifies queue drop retry and close`() {
        val firstDelivery = CompletableFuture<HttpResponse<ByteArray>>()
        val fake = InMemoryAuditHttpClient(
            responses = listOf(firstDelivery, completedResponse(204)),
        )
        val fixture = exporter(fake, queueCapacity = 1, maxAttempts = 2)

        fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.ACCEPTED
        fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_QUEUE_FULL

        firstDelivery.complete(completedResponse(429))
        fake.awaitRequestCount(2, timeout = 5.seconds)
        await
            .atMost(5.seconds)
            .untilAsserted {
                fixture.exporter.snapshot().accepted shouldBeEqualTo 1
                fixture.exporter.snapshot().admitted shouldBeEqualTo 0
            }

        fixture.exporter.close()
        fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_CLOSED
    }

    @Test
    fun `memory fake rejects authorization and cancels gated requests during shutdown`() {
        val gatedResponse = CompletableFuture<HttpResponse<ByteArray>>()
        val fake = InMemoryAuditHttpClient(responses = listOf(gatedResponse))
        val request = HttpRequest
            .newBuilder(URI("https://audit.invalid/in-memory"))
            .POST(HttpRequest.BodyPublishers.ofString("audit"))
            .build()

        val response = fake.sendAsync(request, HttpResponse.BodyHandlers.discarding())
        fake.requestCount shouldBeEqualTo 1
        fake.shutdownNow()

        response.isCancelled.shouldBeTrue()
        fake.awaitTermination(Duration.ofSeconds(1)).shouldBeTrue()
        fake.isTerminated.shouldBeTrue()
        fake.close()
        fake.close()

        val rejected = InMemoryAuditHttpClient()
        val authorizedRequest = HttpRequest
            .newBuilder(URI("https://audit.invalid/in-memory"))
            .header("Authorization", "Bearer secret")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        assertFailsWith<IllegalArgumentException> {
            rejected.sendAsync(authorizedRequest, HttpResponse.BodyHandlers.discarding())
        }
        rejected.close()
    }

    @Test
    fun `terminal HTTP status releases admission without scheduling a retry`() {
        val fake = InMemoryAuditHttpClient(responses = listOf(completedResponse(400)))
        val fixture = exporter(fake, queueCapacity = 1, maxAttempts = 3)

        fixture.exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.ACCEPTED
        await
            .atMost(5.seconds)
            .untilAsserted {
                val snapshot = fixture.exporter.snapshot()
                snapshot.admitted shouldBeEqualTo 0
                snapshot.terminalFailures shouldBeEqualTo 1
                snapshot.scheduledRetries shouldBeEqualTo 0
            }
        fake.requestCount shouldBeEqualTo 1
        fixture.exporter.close()
    }

    private fun exporter(
        client: InMemoryAuditHttpClient,
        queueCapacity: Int,
        maxAttempts: Int,
    ): Fixture {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        schedulers += scheduler
        val options = LeaderAuditExportOptions(
            queueCapacity = queueCapacity,
            maxInFlight = 1,
            maxAttempts = maxAttempts,
            attemptTimeout = Duration.ofSeconds(1),
            initialBackoff = Duration.ofMillis(10),
            maxBackoff = Duration.ofSeconds(1),
            executor = java.util.concurrent.Executor { task -> task.run() },
            scheduler = scheduler,
        )
        return Fixture(
            exporter = HttpLeaderAuditExporter(
                client = client,
                endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(
                    URI("https://audit.example.test/hook"),
                ),
                headers = emptyMap(),
                encoder = io.bluetape4k.leader.audit.http.LeaderAuditPayloadEncoder {
                    LeaderAuditHttpPayload.of("application/json", "{}".toByteArray())
                },
                exportOptions = options,
                httpOptions = io.bluetape4k.leader.audit.http.LeaderAuditHttpOptions.defaults(),
            ),
        )
    }

    private fun event(): LeaderAuditExportEvent.History = LeaderAuditExportEvent.History.from(
        record = LeaderLockHistoryRecord(
            lockName = "job-safety:sample",
            token = "token-secret",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = Instant.parse("2026-08-30T00:00:00Z"),
            lockedUntil = Instant.parse("2026-08-30T00:01:00Z"),
            status = LeaderHistoryStatus.ACQUIRED,
        ),
        sanitizer = LeaderAuditValueSanitizer.Default,
    )

    private fun completedResponse(status: Int): HttpResponse<ByteArray> = object : HttpResponse<ByteArray> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest = HttpRequest
            .newBuilder(URI("https://audit.invalid/in-memory"))
            .build()

        override fun previousResponse(): java.util.Optional<HttpResponse<ByteArray>> = java.util.Optional.empty()

        override fun headers(): java.net.http.HttpHeaders = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): ByteArray = ByteArray(0)

        override fun sslSession(): java.util.Optional<javax.net.ssl.SSLSession> = java.util.Optional.empty()

        override fun uri(): URI = URI("https://audit.invalid/in-memory")

        override fun version(): java.net.http.HttpClient.Version = java.net.http.HttpClient.Version.HTTP_1_1
    }

    private data class Fixture(val exporter: HttpLeaderAuditExporter)
}

package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.audit.LeaderAuditExportOptions
import io.bluetape4k.leader.audit.http.HttpLeaderAuditExporter
import io.bluetape4k.leader.audit.http.LeaderAuditHttpOptions
import io.bluetape4k.leader.audit.http.LeaderAuditTrustedHttpsEndpoint
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.net.URI
import java.util.concurrent.Executors

internal class JobSafetyAuditReportServiceTest {

    companion object: KLogging()

    @Test
    fun `report decodes transient bounded payloads and exposes fixed safe fields`() {
        val store = BoundedAuditPayloadStore(maxEntries = 4, maxBytes = 1024)
        store.add("not-json".toByteArray())
        store.add("{\"status\":\"COMPLETED\",\"safe\":true}".toByteArray())
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val executor = Executors.newSingleThreadExecutor()
        val client = InMemoryAuditHttpClient()
        val exporter = HttpLeaderAuditExporter(
            client = client,
            endpoint = LeaderAuditTrustedHttpsEndpoint.trusted(URI("https://audit.example.test/hook")),
            headers = emptyMap(),
            encoder = { error("encoder is not used by this snapshot test") },
            exportOptions = LeaderAuditExportOptions(
                queueCapacity = 2,
                maxInFlight = 1,
                maxAttempts = 1,
                attemptTimeout = 1.seconds(),
                initialBackoff = 1.millis(),
                maxBackoff = 1.seconds(),
                executor = executor,
                scheduler = scheduler,
            ),
            httpOptions = LeaderAuditHttpOptions.defaults(),
        )

        try {
            val report = JobSafetyAuditReportService(
                transport = "MEMORY",
                enabled = true,
                payloadStore = store,
                exporter = exporter,
            ).report()

            log.debug { "report=$report" }
            report.transport shouldBeEqualTo "MEMORY"
            report.enabled.shouldBeTrue()
            report.retainedPayloadCount shouldBeEqualTo 2
            report.retainedPayloadBytes shouldBeEqualTo store.retainedBytes
            report.malformedPayloadCount shouldBeEqualTo 1
            report.recentEvents.size shouldBeEqualTo 1
            report.recentEvents.single().path("status").asString() shouldBeEqualTo "COMPLETED"
            report.meters shouldBeEqualTo report.meters.sorted()
            report.meters shouldBeEqualTo JobSafetyAuditMeterCatalog.names
            report.meters.none { it.contains("endpoint") || it.contains("authorization") }.shouldBeTrue()
            report.toString() shouldNotContain "audit.example.test"
        } finally {
            exporter.close()
            client.close()
            scheduler.shutdownNow()
            executor.shutdownNow()
        }
    }
}

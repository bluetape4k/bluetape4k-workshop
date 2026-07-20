package io.bluetape4k.workshop.operations.jobconsole.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorJobConsoleHttpTest {
    private val fixture = JobConsoleContainerFixture.shared()
    private var port: Int = 0
    private var stopServer: (() -> Unit)? = null

    @BeforeAll
    fun startServer() {
        fixture.createSchema()
        port = ServerSocket(0).use { it.localPort }
        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = fixture.jdbcUrl
                    username = fixture.databaseUsername
                    password = fixture.databasePassword
                    schema = fixture.schema
                },
            )
        val server = embeddedServer(Netty, port = port) { jobConsoleModule(dataSource, demoEnabled = true) }
        server.start(wait = false)
        stopServer = { server.stop(500, 2_000) }
    }

    @AfterAll
    fun stopServer() {
        fixture.dropSchema()
        stopServer?.invoke()
    }

    @Test
    fun `live REST submit snapshot and cancel share the core contract`() {
        request("GET", "/").body().contains("ETA is an estimate, not an SLA") shouldBeEqualTo true
        request("GET", "/healthz").statusCode() shouldBeEqualTo 200
        val readiness = request("GET", "/readyz")
        readiness.statusCode() shouldBeEqualTo 200
        readiness.body().contains("\"redis\":\"DEGRADED\"") shouldBeEqualTo true

        val submit = request("POST", "/v1/jobs", SUBMIT_BODY, idempotencyKey = "ktor-key")
        submit.statusCode() shouldBeEqualTo 202
        val jobId = requireNotNull(JOB_ID.find(submit.body())?.groupValues?.get(1))

        val snapshot = request("GET", "/v1/jobs/$jobId")
        snapshot.statusCode() shouldBeEqualTo 200
        snapshot.body().contains("\"state\":\"queued\"") shouldBeEqualTo true

        val cancelled = request("POST", "/v1/jobs/$jobId/cancel")
        cancelled.statusCode() shouldBeEqualTo 200
        cancelled.body().contains("\"state\":\"cancelled\"") shouldBeEqualTo true
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        idempotencyKey: String? = null,
    ): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                .header("X-Demo-Tenant", "tenant-a")
                .header("X-Demo-Submitter", "submitter-a")
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey)
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody())
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    companion object {
        private val JOB_ID = Regex("\\\"jobId\\\":\\\"([^\\\"]+)")
        private const val SUBMIT_BODY =
            """{"jobType":"document_export","workUnits":3,"failureMode":"none"}"""
    }
}

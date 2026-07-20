package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Tag("integration")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringJobConsoleHttpTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `live REST submit snapshot and cancel share the core contract`() {
        val submit = request("POST", "/v1/jobs", SUBMIT_BODY, idempotencyKey = "spring-key")
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
        private val fixture = JobConsoleContainerFixture.shared().also { it.createSchema() }
        private val JOB_ID = Regex("\\\"jobId\\\":\\\"([^\\\"]+)")
        private const val SUBMIT_BODY =
            """{"jobType":"document_export","workUnits":3,"failureMode":"none"}"""

        @JvmStatic
        @AfterAll
        fun dropSchema() {
            fixture.dropSchema()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { fixture.jdbcUrl }
            registry.add("spring.datasource.username") { fixture.databaseUsername }
            registry.add("spring.datasource.password") { fixture.databasePassword }
            registry.add("spring.datasource.hikari.schema") { fixture.schema }
        }
    }
}

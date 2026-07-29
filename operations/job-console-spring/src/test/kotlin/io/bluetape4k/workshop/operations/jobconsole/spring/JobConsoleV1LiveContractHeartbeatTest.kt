package io.bluetape4k.workshop.operations.jobconsole.spring

import com.sun.net.httpserver.HttpServer
import io.bluetape4k.assertions.invoking
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleV1LiveContract
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets

class JobConsoleV1LiveContractHeartbeatTest {
    @Test
    fun `heartbeat verification skips an earlier job event`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/jobs/$JOB_ID/events") { exchange ->
            val response =
                """
                id: $EVENT_ID
                event: job.updated
                data: {}

                event: heartbeat
                data: {}

                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            invoking {
                JobConsoleV1LiveContract(URI("http://127.0.0.1:${server.address.port}"))
                    .verifyHeartbeat(JOB_ID)
            }.shouldNotThrow()
        } finally {
            server.stop(0)
        }
    }

    private companion object {
        const val JOB_ID = "019bab33-e657-7000-8000-000000000001"
        const val EVENT_ID = "019bab33-e657-7000-8000-000000000002"
    }
}

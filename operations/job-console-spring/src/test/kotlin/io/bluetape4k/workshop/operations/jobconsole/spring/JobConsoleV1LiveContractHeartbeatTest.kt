package io.bluetape4k.workshop.operations.jobconsole.spring

import com.sun.net.httpserver.HttpServer
import io.bluetape4k.assertions.assertNotFails
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleV1LiveContract
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

/** live Spring SSE가 heartbeat를 보내고 이전 event를 history처럼 재생하지 않는지 검증한다. */
/** live Spring SSE가 heartbeat를 보내고 이전 event를 history처럼 재생하지 않는지 검증한다. */
class JobConsoleV1LiveContractHeartbeatTest {

    private companion object: KLogging() {
/**
 * JOB_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val JOB_ID = "019bab33-e657-7000-8000-000000000001"
/**
 * EVENT_ID은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val EVENT_ID = "019bab33-e657-7000-8000-000000000002"
    }

    @Test
/**
 * 이 시나리오는 "heartbeat verification skips an earlier job event" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

                """.trimIndent().toUtf8Bytes()

            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()

        try {
            assertNotFails {
                JobConsoleV1LiveContract(URI("http://127.0.0.1:${server.address.port}"))
                    .verifyHeartbeat(JOB_ID)
            }
        } finally {
            server.stop(0)
        }
    }
}

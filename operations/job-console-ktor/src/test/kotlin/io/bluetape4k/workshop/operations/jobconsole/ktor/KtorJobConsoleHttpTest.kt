package io.bluetape4k.workshop.operations.jobconsole.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleV1LiveContract
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.ServerSocket
import java.net.URI

/** Ktor live server가 owned worker/outbox scope, REST response, UUIDv7 problem correlation을 함께 검증한다. */
/** Ktor live server가 owned worker/outbox scope, REST response, UUIDv7 problem correlation을 함께 검증한다. */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorJobConsoleHttpTest {

    companion object: KLogging()

/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixture = JobConsoleContainerFixture.shared()
/**
 * port은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var port: Int = 0
/**
 * stopServer은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private var stopServer: (() -> Unit)? = null

    @BeforeAll
    fun startServer() {
        fixture.createSchema()
        port = ServerSocket(0).use { it.localPort }
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.databaseUsername
                password = fixture.databasePassword
                schema = fixture.schema
            },
        )
        val server = embeddedServer(Netty, port = port) {
            jobConsoleModule(dataSource, demoEnabled = true, boundedWaitEnabled = true)
        }
        server.start(wait = false)
        stopServer = { server.stop(500, 2_000) }
    }

    @AfterAll
    fun stopServer() {
        stopServer?.invoke()
        fixture.dropSchema()
    }

    @Test
/**
 * 이 시나리오는 "live REST submit is completed by the owned worker lifecycle" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live REST submit is completed by the owned worker lifecycle`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyOwnedWorkerLifecycle("ktor-key")
    }

    @Test
/**
 * 이 시나리오는 "live problem request IDs use UUID version seven" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live problem request IDs use UUID version seven`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyProblemRequestIdUsesUuidV7()
    }
}

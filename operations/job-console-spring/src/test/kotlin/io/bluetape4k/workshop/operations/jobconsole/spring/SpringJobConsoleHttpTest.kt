package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleV1LiveContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI

/** Spring live server가 worker lifecycle, REST response, redacted problem correlation을 함께 보존하는지 검증한다. */
/** Spring live server가 worker lifecycle, REST response, redacted problem correlation을 함께 보존하는지 검증한다. */
@Tag("integration")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringJobConsoleHttpTest {

    @LocalServerPort
/**
 * port은 테스트 fixture가 보관하는 port 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private var port: Int = 0

    @Test
/**
 * 이 시나리오는 "live REST submit is completed by the owned worker lifecycle" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `live REST submit is completed by the owned worker lifecycle`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyOwnedWorkerLifecycle("spring-key")
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

    companion object: KLogging() {
        private val fixture = JobConsoleContainerFixture.shared().also { it.createSchema() }

        @JvmStatic
        @AfterAll
        fun dropSchema() {
            fixture.dropSchema()
        }

        @JvmStatic
        @DynamicPropertySource
/**
 * properties은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { fixture.jdbcUrl }
            registry.add("spring.datasource.username") { fixture.databaseUsername }
            registry.add("spring.datasource.password") { fixture.databasePassword }
            registry.add("spring.datasource.hikari.schema") { fixture.schema }
            registry.add("job-console.bounded-wait.enabled") { "true" }
        }
    }
}

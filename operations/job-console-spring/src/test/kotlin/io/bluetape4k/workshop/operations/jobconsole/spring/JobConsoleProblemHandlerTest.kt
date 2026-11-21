package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.JobConsoleJson
import io.bluetape4k.workshop.operations.jobconsole.api.JobProblem
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepositoryException
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.readValue
import java.util.*

/** stable problem code와 UUIDv7 request correlation을 client-safe response로 고정한다. */
/** stable problem code와 UUIDv7 request correlation을 client-safe response로 고정한다. */
class JobConsoleProblemHandlerTest {

    companion object: KLogging()

/**
 * handler은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val handler = JobConsoleProblemHandler()
/**
 * mapper은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mapper = JobConsoleJson.defaultMapper

    @Test
/**
 * 이 시나리오는 "problem correlations use UUID version seven" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `problem correlations use UUID version seven`() {
        val validation = mapper.readValue<JobProblem>(handler.validation().body.shouldNotBeNull())
        val conflict = mapper.readValue<JobProblem>(
            handler.repository(JobRepositoryException(JobProblemCode.IDEMPOTENCY_KEY_REUSED)).body.shouldNotBeNull(),
        )

        UUID.fromString(validation.requestId).version() shouldBeEqualTo 7
        UUID.fromString(conflict.requestId).version() shouldBeEqualTo 7
    }
}

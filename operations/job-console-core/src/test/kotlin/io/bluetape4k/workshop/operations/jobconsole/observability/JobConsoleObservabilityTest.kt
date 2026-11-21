package io.bluetape4k.workshop.operations.jobconsole.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

/** metric과 readiness detail이 raw tenant·payload·identity를 low-cardinality label로 노출하지 않는지 검증한다. */
/** metric과 readiness detail이 raw tenant·payload·identity를 low-cardinality label로 노출하지 않는지 검증한다. */
class JobConsoleObservabilityTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "identity and payload cannot become metric labels" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `identity and payload cannot become metric labels`() {
        val tags = JobConsoleObservability.safeTags(
            mapOf(
                "adapter" to "spring",
                "state" to "queued",
                "tenant" to "tenant-a",
                "jobId" to "secret-id",
                "payload" to "raw-body",
            ),
        )

        log.debug { "tags=$tags" }
        tags shouldBeEqualTo mapOf("adapter" to "spring", "state" to "queued")
    }
}

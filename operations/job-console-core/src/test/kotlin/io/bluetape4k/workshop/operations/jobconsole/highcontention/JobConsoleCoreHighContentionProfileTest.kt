package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("high-contention")
/**
 * JobConsoleCoreHighContentionProfileTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleCoreHighContentionProfileTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "selected Job core profile preserves PostgreSQL authority and writes a validated report"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `selected Job core profile preserves PostgreSQL authority and writes a validated report`() {
        JobConsoleLiveProfileRunner(
            implementation = "job-core",
            adapterFactory = { _, profile ->
                JobConsoleHighContentionAdapter.create(profile)
            },
        ).run()
    }
}

package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Ktor high-contention profile이 HTTP·Redis·coroutine lifecycle 경계를 함께 통과하는지 검증한다. */
/** Ktor high-contention profile이 HTTP·Redis·coroutine lifecycle 경계를 함께 통과하는지 검증한다. */
@Tag("high-contention")
class KtorJobConsoleHighContentionProfileTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "selected Ktor profile crosses HTTP Redis and server lifecycle boundaries" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `selected Ktor profile crosses HTTP Redis and server lifecycle boundaries`() {
        JobConsoleLiveProfileRunner(
            implementation = "job-ktor",
            adapterFactory = KtorJobConsoleLiveAdapter::create,
        ).run()
    }
}

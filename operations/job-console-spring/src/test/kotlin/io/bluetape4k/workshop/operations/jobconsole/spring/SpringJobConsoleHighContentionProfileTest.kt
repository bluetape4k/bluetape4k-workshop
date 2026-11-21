package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Spring high-contention profile이 HTTP·Redis·application lifecycle을 관통해 core 불변식을 보존하는지 검증한다. */
/** Spring high-contention profile이 HTTP·Redis·application lifecycle을 관통해 core 불변식을 보존하는지 검증한다. */
@Tag("high-contention")
class SpringJobConsoleHighContentionProfileTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "selected Spring profile crosses HTTP Redis and application lifecycle boundaries" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `selected Spring profile crosses HTTP Redis and application lifecycle boundaries`() {
        JobConsoleLiveProfileRunner(
            implementation = "job-spring",
            adapterFactory = SpringJobConsoleLiveAdapter::create,
        ).run()
    }
}

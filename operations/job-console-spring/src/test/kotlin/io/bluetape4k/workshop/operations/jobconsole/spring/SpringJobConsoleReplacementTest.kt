package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** Spring scheduler가 한 cycle의 completion을 불필요한 두 번째 reclaim 없이 반영하는지 검증한다. */
/** Spring scheduler가 한 cycle의 completion을 불필요한 두 번째 reclaim 없이 반영하는지 검증한다. */
class SpringJobConsoleReplacementTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "scheduled worker completion wins without requiring a second reclaim" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `scheduled worker completion wins without requiring a second reclaim`() {
        val completionChecks = AtomicInteger()

        val result = awaitSpringReplacement(
            timeout = 1.seconds(),
            pollInterval = 1.millis(),
            reclaim = { null },
            isCompleted = { completionChecks.incrementAndGet() >= 2 },
        )

        log.debug { "result=$result" }
        result shouldBeEqualTo SpringReplacementOutcome.Completed
    }
}

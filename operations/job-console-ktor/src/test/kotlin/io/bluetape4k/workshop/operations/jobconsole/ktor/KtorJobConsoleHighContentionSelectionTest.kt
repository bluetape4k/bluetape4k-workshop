package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionContractLoader
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionMode
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** 선언된 Ktor profile이 정확히 하나의 live action과 correctness gate를 갖는지 검증한다. */
/** 선언된 Ktor profile이 정확히 하나의 live action과 correctness gate를 갖는지 검증한다. */
class KtorJobConsoleHighContentionSelectionTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "every declared Job profile has exactly one Ktor live action" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `every declared Job profile has exactly one Ktor live action`() {
        val contractRoot = Path.of(
            System.getProperty("highContentionContractRoot")
                .requireNotNull("highContentionContractRoot"),
        )
        val manifestProfileIds = HighContentionContractLoader().load(
            contractRoot = contractRoot,
            mode = HighContentionMode.CI_CORRECTNESS,
            profileId = "burst",
            implementation = "job-ktor",
        ).suite.entries
            .map { it.profileId }
            .toSet()

        KtorJobConsoleProfileAction.entries
            .map { it.profileId }
            .toSet() shouldBeEqualTo manifestProfileIds
    }
}

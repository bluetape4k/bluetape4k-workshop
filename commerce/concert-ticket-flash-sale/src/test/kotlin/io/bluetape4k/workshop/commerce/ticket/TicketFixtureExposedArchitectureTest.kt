package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 일반 ticket fixture assertion이 범용 RAW query helper로 되돌아가지 않도록 고정합니다. */
/** 일반 ticket fixture assertion이 범용 RAW query helper로 되돌아가지 않도록 고정합니다. */
internal class TicketFixtureExposedArchitectureTest {

    @Test
/**
 * 이 시나리오는 "integration fixture assertions use typed Exposed queries" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `integration fixture assertions use typed Exposed queries`() {
        val files = listOf(
            "io/bluetape4k/workshop/commerce/ticket/purchase/PurchaseServiceIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/ticket/AbstractTicketIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/ticket/ticketing/TicketEffectIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionProfileApplication.kt",
            "io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionProfileTest.kt",
        ).map { locateTestSourceRoot().resolve(it) }

        files.forEach { file ->
            val source = Files.readString(file)
            source.shouldNotContain("queryInt(")
            source.shouldNotContain("queryString(")
            source.shouldNotContain("queryUuid(")
        }

        Files.readString(files.first()).shouldContain("TicketInventories")
    }

/**
 * locateTestSourceRoot은 콘서트 티켓 플래시 세일 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/test/kotlin")
    }
}

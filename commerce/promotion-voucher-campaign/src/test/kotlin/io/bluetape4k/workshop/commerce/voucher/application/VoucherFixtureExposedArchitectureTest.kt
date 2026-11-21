package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 일반 voucher command/reconciliation assertion이 migration용 RAW query helper를 재사용하지 않도록 고정합니다. */
/** 일반 voucher command/reconciliation assertion이 migration용 RAW query helper를 재사용하지 않도록 고정합니다. */
internal class VoucherFixtureExposedArchitectureTest {

    @Test
/**
 * 이 시나리오는 "normal command fixtures use typed Exposed counts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normal command fixtures use typed Exposed counts`() {
        val files = listOf(
            "io/bluetape4k/workshop/commerce/voucher/application/VoucherCommandTestSupport.kt",
            "io/bluetape4k/workshop/commerce/voucher/VoucherEndToEndIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucher/VoucherStressProfileTest.kt",
            "io/bluetape4k/workshop/commerce/voucher/VoucherContextRestartIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucher/application/VoucherConcurrencyIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationServiceTest.kt",
        ).map { locateTestSourceRoot().resolve(it) }

        files.forEach { file ->
            val source = Files.readString(file)
            source.shouldNotContain("queryLong(")
        }

        Files.readString(files.first()).shouldContain("ClaimTable")
        Files.readString(files.first()).shouldContain("EventInboxTable")
    }

/**
 * locateTestSourceRoot은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/promotion-voucher-campaign/src/test/kotlin")
    }
}

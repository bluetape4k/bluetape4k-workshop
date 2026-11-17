package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 일반 voucher command/reconciliation assertion이 migration용 RAW query helper를 재사용하지 않도록 고정합니다. */
internal class VoucherFixtureExposedArchitectureTest {

    @Test
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

    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/promotion-voucher-campaign/src/test/kotlin")
    }
}

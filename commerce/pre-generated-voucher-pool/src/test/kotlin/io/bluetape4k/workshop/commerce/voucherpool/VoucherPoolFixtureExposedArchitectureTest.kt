package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class VoucherPoolFixtureExposedArchitectureTest {

    @Test
    fun `일반 상태 assertion fixture는 Exposed typed query를 사용한다`() {
        val files = listOf(
            "io/bluetape4k/workshop/commerce/voucherpool/application/VoucherPoolLifecycleIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucherpool/application/VoucherBatchIngestIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRetentionIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolIdempotencyIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRepositoryIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/voucherpool/web/OperatorVoucherPoolWebIntegrationTest.kt",
        ).map { locateTestSourceRoot().resolve(it) }

        files.forEach { file ->
            val source = Files.readString(file)
            source.shouldNotContain("singleInt(")
            source.shouldNotContain("singleLong(")
            source.shouldNotContain("queryLong(")
            source.shouldNotContain("scalar(")
        }

        Files.readString(files.first()).shouldContain("VoucherPoolEntryTable")
        Files.readString(files[1]).shouldContain("VoucherPoolBatchTable")
        Files.readString(files[2]).shouldContain("VoucherPoolHttpIdempotencyTable")

        val queryPlanSource = Files.readString(files[4])
        queryPlanSource.shouldContain(".explain(analyze = true")
        queryPlanSource.shouldNotContain("explain(repository.workerCandidatePlanSql")
        queryPlanSource.shouldNotContain("explain(repository.poolDepthPlanSql")
    }

    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/pre-generated-voucher-pool/src/test/kotlin")
    }
}

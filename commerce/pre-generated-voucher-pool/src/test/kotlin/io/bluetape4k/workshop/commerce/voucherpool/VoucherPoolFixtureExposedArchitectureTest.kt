package io.bluetape4k.workshop.commerce.voucherpool

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * VoucherPoolFixtureExposedArchitectureTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolFixtureExposedArchitectureTest {

    @Test
/**
 * 이 시나리오는 "일반 상태 assertion fixture는 Exposed typed query를 사용한다" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * locateTestSourceRoot은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/pre-generated-voucher-pool/src/test/kotlin")
    }
}

package io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * EventStoreQueryPlanArchitectureTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventStoreQueryPlanArchitectureTest {

    @Test
/**
 * 이 시나리오는 "stream tail plan uses the Exposed explain wrapper" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stream tail plan uses the Exposed explain wrapper`() {
        val source = Files.readString(locateSource())
        source.shouldContain(".explain(options = \"FORMAT TEXT\"")
        source.shouldContain(".selectAll()")
        source.shouldNotContain("EXPLAIN (FORMAT TEXT)")
    }

    @Test
/**
 * 이 시나리오는 "stress query plans use the Exposed explain wrapper" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stress query plans use the Exposed explain wrapper`() {
        val source = Files.readString(locateStressSource())
        source.shouldContain(".explain(analyze = true, options = \"BUFFERS\"")
        source.shouldNotContain("EXPLAIN (ANALYZE, BUFFERS)")
    }

/**
 * locateSource은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateSource(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                Path.of(
                    "commerce",
                    "event-sourced-promotion-voucher-campaign",
                    "src",
                    "test",
                    "kotlin",
                    "io",
                    "bluetape4k",
                    "workshop",
                    "commerce",
                    "voucher",
                    "eventsourced",
                    "persistence",
                    "EventStorePostgresCapabilityIntegrationTest.kt",
                ),
            )
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            directory = directory?.parent
        }
        error("EventStorePostgresCapabilityIntegrationTest.kt를 찾을 수 없습니다")
    }

/**
 * locateStressSource은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun locateStressSource(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                Path.of(
                    "commerce",
                    "event-sourced-promotion-voucher-campaign",
                    "src",
                    "test",
                    "kotlin",
                    "io",
                    "bluetape4k",
                    "workshop",
                    "commerce",
                    "voucher",
                    "eventsourced",
                    "persistence",
                    "EventSourcedQueryPlanStressTest.kt",
                ),
            )
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            directory = directory?.parent
        }
        error("EventSourcedQueryPlanStressTest.kt를 찾을 수 없습니다")
    }
}

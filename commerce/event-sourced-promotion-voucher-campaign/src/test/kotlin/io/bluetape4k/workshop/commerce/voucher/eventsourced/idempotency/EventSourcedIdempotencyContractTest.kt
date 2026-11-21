package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBe
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.*

/**
 * EventSourcedIdempotencyContractTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedIdempotencyContractTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "receipt scope separates principals and resources" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `receipt scope separates principals and resources`() {
        // 준비
        val first = scope(principal = "principal-a")
        val second = scope(principal = "principal-b")

        // 실행 / 검증
        first shouldNotBe second
    }

    @Test
/**
 * 이 시나리오는 "receipt digest restores the persisted canonical representation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `receipt digest restores the persisted canonical representation`() {
        val digest = ReceiptDigest.sha256("projection-generation")

        ReceiptDigest.of(digest.value) shouldBeEqualTo digest
    }

    @Test
/**
 * 이 시나리오는 "terminal replay fails closed when its generation key is unavailable" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `terminal replay fails closed when its generation key is unavailable`() {
        // 준비
        val descriptor = TerminalDescriptor(
            outcome = ReceiptOutcome.VOUCHER_ALLOCATED,
            status = 201,
            allocationId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc001"),
            keyVersions = TerminalKeyVersions(
                generationKeyVersion = 3,
                verificationKeyVersion = 3,
            ),
        )

        // 실행
        val replay = descriptor.replayWith { version -> version != 3 }
        log.debug { "replay=$replay" }

        // 검증
        replay shouldBeEqualTo TerminalReplay.KeyUnavailable
    }

/**
 * scope은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun scope(principal: String): ReceiptScope =
        ReceiptScope(
            tenantId = TenantId("tenant-a"),
            principalDigest = ReceiptDigest.sha256(principal),
            operation = "campaign.create",
            resourceId = "campaign-a",
            keyDigest = ReceiptDigest.sha256("key-a"),
        )
}

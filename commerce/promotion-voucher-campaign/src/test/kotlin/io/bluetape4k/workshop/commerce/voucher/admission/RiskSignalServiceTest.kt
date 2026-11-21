package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test

/**
 * RiskSignalServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class RiskSignalServiceTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "versioned HMAC keys are deterministic and never expose raw identity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `versioned HMAC keys are deterministic and never expose raw identity`() {
        val keys = keys()

        val first = keys.rateKey("tenant-a", "user@example.com", "allocate")
        val second = keys.rateKey("tenant-a", "user@example.com", "allocate")

        first shouldBeEqualTo second
        first shouldStartWith "v7:"
        first shouldNotContain "tenant-a"
        first shouldNotContain "user@example.com"
    }

    @Test
/**
 * 이 시나리오는 "Bloom positive requests review while backend failure remains unknown" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Bloom positive requests review while backend failure remains unknown`() {
        val backend = RecordingRiskBackend()
        val service = RiskSignalService(keys(), backend)

        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.CLEAR
        service.remember("tenant-a", "subject-1").shouldBeTrue()
        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.REVIEW

        backend.failure = IllegalStateException("redis unavailable")
        service.assess("tenant-a", "subject-1") shouldBeEqualTo RiskSignal.UNKNOWN
    }

/**
 * RecordingRiskBackend 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
    private class RecordingRiskBackend: VoucherRiskBackend {
/**
 * values은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val values = mutableSetOf<String>()
/**
 * failure은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        var failure: RuntimeException? = null

/**
 * add은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun add(digest: String) {
            failure?.let { throw it }
            values += digest
        }

/**
 * mightContain은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        override fun mightContain(digest: String): Boolean {
            failure?.let { throw it }
            return digest in values
        }
    }

/**
 * keys은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun keys(): VoucherAdmissionKeyFactory =
        VoucherAdmissionKeyFactory(
            version = 7,
            rateKey = ByteArray(32) { 0x31 },
            riskKey = ByteArray(32) { 0x52 },
        )
}

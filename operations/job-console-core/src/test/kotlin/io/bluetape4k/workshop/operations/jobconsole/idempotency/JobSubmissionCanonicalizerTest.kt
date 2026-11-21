package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import org.junit.jupiter.api.Test

/** 재시도 request의 canonical digest가 기본값·typed field·scope·원본 key를 안정적으로 구분하는지 검증한다. */
/** 재시도 request의 canonical digest가 기본값·typed field·scope·원본 key를 안정적으로 구분하는지 검증한다. */
class JobSubmissionCanonicalizerTest {

    companion object: KLogging()

/**
 * canonicalizer은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val canonicalizer = JobSubmissionCanonicalizer()
/**
 * scope은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val scope = DemoCallerScope(tenantId = "tenant-a", submitterHash = "submitter-a")

    @Test
/**
 * 이 시나리오는 "omitted failure mode has the same canonical fingerprint as explicit none" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `omitted failure mode has the same canonical fingerprint as explicit none`() {
        val omitted = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10)
        val explicit = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)

        canonicalizer.fingerprint(omitted) shouldBeEqualTo canonicalizer.fingerprint(explicit)
    }

    @Test
/**
 * 이 시나리오는 "canonical fingerprint changes when a typed request field changes" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `canonical fingerprint changes when a typed request field changes`() {
        val original = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
        val differentUnits = original.copy(workUnits = 11)
        val differentFailureMode = original.copy(failureMode = FailureMode.RETRY_ONCE)

        canonicalizer.fingerprint(original) shouldNotBeEqualTo canonicalizer.fingerprint(differentUnits)
        canonicalizer.fingerprint(original) shouldNotBeEqualTo canonicalizer.fingerprint(differentFailureMode)
    }

    @Test
/**
 * 이 시나리오는 "raw key bytes are preserved and bounded" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `raw key bytes are preserved and bounded`() {
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, " ") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "\t") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "x".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "é") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "first,second") }

        canonicalizer.keyHash(scope, "Key-A") shouldNotBeEqualTo canonicalizer.keyHash(scope, "key-a")
    }

    @Test
/**
 * 이 시나리오는 "scope is part of the key hash and cannot cross tenant or submitter boundaries" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `scope is part of the key hash and cannot cross tenant or submitter boundaries`() {
        val key = "request-key"
        val sameTenantDifferentSubmitter = DemoCallerScope("tenant-a", "submitter-b")
        val differentTenantSameSubmitter = DemoCallerScope("tenant-b", "submitter-a")

        canonicalizer.keyHash(scope, key) shouldNotBeEqualTo canonicalizer.keyHash(sameTenantDifferentSubmitter, key)
        canonicalizer.keyHash(scope, key) shouldNotBeEqualTo canonicalizer.keyHash(differentTenantSameSubmitter, key)
    }
}

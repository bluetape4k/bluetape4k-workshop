package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.codec.encodeBase58
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.security.MessageDigest

/**
 * JobConsoleScenario 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
data class JobConsoleScenario(
/**
 * scope은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val scope: DemoCallerScope = DemoCallerScope("tenant-a", "submitter-a"),
/**
 * idempotencyKey은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val idempotencyKey: String = "scenario-key",
/**
 * request은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val request: SubmitJobRequest = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 3),
) {
    companion object {
/**
 * fromSeed은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        fun fromSeed(
            seed: String,
            ordinal: Int,
            workUnits: Int = 3,
        ): JobConsoleScenario {
            ordinal.requireZeroOrPositiveNumber("ordinal")
/**
 * suffix은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val suffix = MessageDigest.getInstance("SHA-256")
                .digest("$seed:$ordinal".toByteArray(Charsets.UTF_8))
                .copyOf(12)
                .encodeBase58()
            return JobConsoleScenario(
                scope = DemoCallerScope("tenant-$suffix", "submitter-$suffix"),
                idempotencyKey = "request-$suffix",
                request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits),
            )
        }
    }
}

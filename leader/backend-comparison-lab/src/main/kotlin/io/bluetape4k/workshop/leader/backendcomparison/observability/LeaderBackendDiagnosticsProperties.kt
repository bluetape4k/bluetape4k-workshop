package io.bluetape4k.workshop.leader.backendcomparison.observability

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * backend comparison lab의 credential-free diagnostics 설정입니다.
 *
 * [backendId]는 [io.bluetape4k.workshop.leader.backendcomparison.service.LeaderBackendCatalog]의
 * profile id를 사용합니다. [probeOutcome]은 실제 backend 호출이 아니라 bounded probe
 * 경계를 학습하기 위한 결정론적인 결과입니다.
 */
@ConfigurationProperties("workshop.leader")
data class LeaderBackendDiagnosticsProperties(
    /** diagnostics 응답에 사용할 workshop profile id입니다. */
    val backendId: String = "redis-lettuce",
    /** active probe가 반환할 결정론적인 결과입니다. */
    val probeOutcome: ProbeOutcome = ProbeOutcome.UNKNOWN,
)

/**
 * 실제 backend 없이 active diagnostics 경계를 재현하는 결과입니다.
 */
enum class ProbeOutcome {
    /** backend 연결을 확인한 상태입니다. */
    UP,

    /** backend 연결이 끊긴 상태입니다. */
    DOWN,

    /** provider가 연결을 확정하지 못한 상태입니다. */
    UNKNOWN,

    /** provider가 bounded probe를 지원하지 않는 상태입니다. */
    UNSUPPORTED,

    /** provider callback이 일반 예외를 발생시킨 상태입니다. */
    EXCEPTION,

    /** provider callback이 취소된 상태입니다. */
    CANCELLED,
}

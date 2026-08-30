package io.bluetape4k.workshop.leader.jobsafety.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * job-safety 예제가 lease-extension observation을 선택하는 설정입니다.
 *
 * lock name과 leader id는 기본적으로 기존 job-safety hash sanitizer를 거친
 * high-cardinality tag로만 기록합니다. 원문과 exception message는 기본값에서
 * observation 결과에 포함하지 않습니다.
 */
@ConfigurationProperties("bluetape4k.leader.observation")
data class JobSafetyLeaderObservationProperties(
    val enabled: Boolean = true,
    val includeLockName: Boolean = false,
    val includeLeaderId: Boolean = false,
    val includeExceptionDetails: Boolean = false,
)

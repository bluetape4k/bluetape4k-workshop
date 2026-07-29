package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * lock 이름, report, metric 예제에서 사용하는 민감하지 않은 tenant alias이다.
 *
 * 값은 metric/log에 안전한 소문자 alias로 canonicalize된다.
 * 고객명, 이메일, account id, 기타 민감 식별자는 이 값을 만들기 전에 안정적인 alias로 매핑해야 한다.
 */
@ConsistentCopyVisibility
data class TenantId private constructor(
    val value: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * 호출자 입력을 검증하고 canonicalize한 뒤 tenant alias를 만든다.
         */
        operator fun invoke(raw: String): TenantId =
            TenantId(normalizeTenantAlias(raw, "tenantId"))
    }

    override fun toString(): String = value
}

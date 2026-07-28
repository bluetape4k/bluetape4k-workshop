package io.bluetape4k.workshop.leader.tenantscheduler.domain

import java.io.Serializable

/**
 * tenant namespace 확장 전에 사용하는 tenant-local scheduled job 이름이다.
 *
 * backend lock 이름은 수동 문자열 결합이 아니라 `TenantLockNamespace`를 통해 도출해야 한다.
 */
@ConsistentCopyVisibility
data class TenantJobName private constructor(
    val value: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L

        /**
         * 안전한 tenant-local job 이름을 만든다.
         */
        operator fun invoke(raw: String): TenantJobName =
            TenantJobName(normalizeTenantAlias(raw, "jobName"))
    }

    override fun toString(): String = value
}

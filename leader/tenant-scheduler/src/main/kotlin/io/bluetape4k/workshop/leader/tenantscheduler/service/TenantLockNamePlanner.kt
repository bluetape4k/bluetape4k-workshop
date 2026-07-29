package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.leader.TenantLockNamespace
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantJobName

/**
 * tenant-local 워크숍 입력에서 backend lock 이름을 도출한다.
 *
 * 이 planner는 namespace 형식화와 최종 lock-name 검증을 `TenantLockNamespace`에 위임한다.
 * 따라서 워크숍도 실제 leader elector와 같은 규칙을 사용한다.
 */
class TenantLockNamePlanner {

    /**
     * tenant-local scheduled job에 대응하는 backend lock 이름을 반환한다.
     */
    fun lockName(
        tenantId: TenantId,
        jobName: TenantJobName,
    ): String =
        TenantLockNamespace(tenantId.value).lockName(jobName.value)
}

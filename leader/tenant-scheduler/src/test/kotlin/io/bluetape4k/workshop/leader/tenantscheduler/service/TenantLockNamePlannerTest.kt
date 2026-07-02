package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantJobName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantLockNamePlannerTest {

    private val planner = TenantLockNamePlanner()

    @Test
    fun `derives backend lock name through TenantLockNamespace`() {
        val lockName = planner.lockName(
            tenantId = TenantId("Tenant-A"),
            jobName = TenantJobName("Invoice-Sync"),
        )

        lockName shouldBeEqualTo "tenant:tenant-a:invoice-sync"
    }
}

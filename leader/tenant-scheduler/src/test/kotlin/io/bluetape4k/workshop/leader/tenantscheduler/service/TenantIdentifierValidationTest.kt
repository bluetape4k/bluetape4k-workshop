package io.bluetape4k.workshop.leader.tenantscheduler.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantId
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantJobName
import io.bluetape4k.workshop.leader.tenantscheduler.domain.TenantNodeId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIdentifierValidationTest {

    @Test
    fun `tenant id canonicalizes safe aliases`() {
        TenantId("Tenant-A").value shouldBeEqualTo "tenant-a"
        TenantId("tenant-b").value shouldBeEqualTo "tenant-b"
    }

    @Test
    fun `job and node names canonicalize safe aliases`() {
        TenantJobName("Invoice-Sync").value shouldBeEqualTo "invoice-sync"
        TenantNodeId("Node-A").value shouldBeEqualTo "node-a"
    }

    @Test
    fun `tenant id rejects unsafe aliases without echoing raw input`() {
        val unsafeValues = listOf(
            "",
            "  ",
            "a",
            "tenant:a",
            "tenant_a",
            "tenant.a",
            "tenant/a",
            "tenant a",
            "tenant\nalpha",
            "user@example.test",
            "acct-123456789012",
            "aws-123456789012",
            "customer-123456",
            "tenant-${"a".repeat(65)}",
        )
        val sensitiveSamples = setOf(
            "tenant\nalpha",
            "user@example.test",
            "acct-123456789012",
            "aws-123456789012",
            "customer-123456",
        )

        unsafeValues.forEach { raw ->
            val error = assertFailsWith<IllegalArgumentException> {
                TenantId(raw)
            }

            val message = error.message.orEmpty()
            message.isNotBlank().shouldBeTrue()
            if (raw in sensitiveSamples) {
                (!message.contains(raw)).shouldBeTrue()
            }
        }
    }

    @Test
    fun `job and node names reject unsafe aliases`() {
        listOf(
            { TenantJobName("invoice:sync") },
            { TenantJobName("invoice_sync") },
            { TenantJobName("ops@example.test") },
            { TenantNodeId("node:1") },
            { TenantNodeId("node_1") },
            { TenantNodeId("acct-123456789012") },
        ).forEach { factory ->
            assertFailsWith<IllegalArgumentException> {
                factory()
            }
        }
    }
}

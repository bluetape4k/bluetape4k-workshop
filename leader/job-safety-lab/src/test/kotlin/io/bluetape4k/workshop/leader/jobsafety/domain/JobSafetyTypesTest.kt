package io.bluetape4k.workshop.leader.jobsafety.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.YearMonth

internal class JobSafetyTypesTest {
    @Test
    fun `fencing token is positive and orderable`() {
        assertFailsWith<IllegalArgumentException> { FencingToken(0L) }
        assertFailsWith<IllegalArgumentException> { FencingToken(-1L) }

        (FencingToken(42L) > FencingToken(41L)).shouldBeTrue()
        (FencingToken(41L) > FencingToken(42L)).shouldBeFalse()
    }

    @Test
    fun `identifiers reject blank values`() {
        assertFailsWith<IllegalArgumentException> { LeaderOwnerId(" ") }
        assertFailsWith<IllegalArgumentException> { FencingOwnerId("") }
        assertFailsWith<IllegalArgumentException> { TenantId("\t") }
        assertFailsWith<IllegalArgumentException> { JobName("\n") }
        assertFailsWith<IllegalArgumentException> { RegionId(" ") }
        assertFailsWith<IllegalArgumentException> { OperationId("") }
    }

    @Test
    fun `summary conflict key is stable across retries`() {
        val tenantId = TenantId("tenant-a")

        ConflictKey.summary(tenantId, YearMonth.of(2026, 7)).value shouldBeEqualTo
            "summary:tenant-a:2026-07"
        ConflictKey.summary(tenantId, YearMonth.of(2026, 7)) shouldBeEqualTo
            ConflictKey.summary(tenantId, YearMonth.of(2026, 7))
    }

    @Test
    fun `authority revisions epochs and versions must be positive`() {
        assertFailsWith<IllegalArgumentException> { MembershipRevision(0L) }
        assertFailsWith<IllegalArgumentException> { RegionEpoch(0L) }
        assertFailsWith<IllegalArgumentException> { NamespaceEpoch(0L) }
        assertFailsWith<IllegalArgumentException> { ExecutionContractVersion(0) }

        MembershipRevision(7L).value shouldBeEqualTo 7L
        RegionEpoch(3L).value shouldBeEqualTo 3L
        NamespaceEpoch(2L).value shouldBeEqualTo 2L
        ExecutionContractVersion(4).value shouldBeEqualTo 4
    }
}

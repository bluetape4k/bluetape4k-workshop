package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotEquals

class JobSubmissionCanonicalizerTest {

    private val canonicalizer = JobSubmissionCanonicalizer()
    private val scope = DemoCallerScope(tenantId = "tenant-a", submitterHash = "submitter-a")

    @Test
    fun `omitted failure mode has the same canonical fingerprint as explicit none`() {
        val omitted = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10)
        val explicit = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)

        canonicalizer.fingerprint(omitted) shouldBeEqualTo canonicalizer.fingerprint(explicit)
    }

    @Test
    fun `canonical fingerprint changes when a typed request field changes`() {
        val original = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 10, FailureMode.NONE)
        val differentUnits = original.copy(workUnits = 11)
        val differentFailureMode = original.copy(failureMode = FailureMode.RETRY_ONCE)

        assertNotEquals(canonicalizer.fingerprint(original), canonicalizer.fingerprint(differentUnits))
        assertNotEquals(canonicalizer.fingerprint(original), canonicalizer.fingerprint(differentFailureMode))
    }

    @Test
    fun `raw key bytes are preserved and bounded`() {
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, " ") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "\t") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "x".repeat(256)) }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "é") }
        assertFailsWith<IllegalArgumentException> { canonicalizer.keyHash(scope, "first,second") }

        assertNotEquals(
            canonicalizer.keyHash(scope, "Key-A"),
            canonicalizer.keyHash(scope, "key-a"),
        )
    }

    @Test
    fun `scope is part of the key hash and cannot cross tenant or submitter boundaries`() {
        val key = "request-key"
        val sameTenantDifferentSubmitter = DemoCallerScope("tenant-a", "submitter-b")
        val differentTenantSameSubmitter = DemoCallerScope("tenant-b", "submitter-a")

        assertNotEquals(canonicalizer.keyHash(scope, key), canonicalizer.keyHash(sameTenantDifferentSubmitter, key))
        assertNotEquals(canonicalizer.keyHash(scope, key), canonicalizer.keyHash(differentTenantSameSubmitter, key))
    }
}

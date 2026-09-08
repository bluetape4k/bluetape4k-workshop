package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

internal class JobSafetyAuditScopeTest {

    @Test
    fun `application scopes own independent supervisor jobs and close idempotently`() = runSuspendIO {
        val first = JobSafetyAuditScope()
        val second = JobSafetyAuditScope()
        val child = first.launch { awaitCancellation() }
        yield()

        first.close()
        first.close()
        child.join()

        first.scopeClosed.shouldBeTrue()
        first.scopeCancelled.shouldBeTrue()
        first.isActive.shouldBeFalse()
        child.isCancelled.shouldBeTrue()
        second.scopeClosed.shouldBeFalse()
        second.scopeCancelled.shouldBeFalse()
        second.isActive.shouldBeTrue()

        second.close()
        second.scopeClosed.shouldBeTrue()
        second.scopeCancelled.shouldBeTrue()
    }
}

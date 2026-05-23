package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * T5: Lease expiry educational smoke test.
 *
 * Demonstrates what happens when a leader holds the lock beyond the leaseTime TTL.
 * The outcome (second elector wins or fails) is library-contract dependent and may
 * vary by Redis timing — this test is intentionally educational, not a strict assertion.
 *
 * Excluded from the default `test` task via `junit.jupiter.execution.exclude.tags=smoke`.
 * Run explicitly in nightly or via: `./gradlew test -Djunit.jupiter.execution.exclude.tags=`
 */
@Tag("smoke")
class LeaseExpiryTest : AbstractLeaderElectionTest() {

    @Test
    fun `leader holding lock beyond leaseTime allows second elector to attempt acquisition`() {
        val lockName = "test:t5:${UUID.randomUUID()}"
        val shortLeaseOptions = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 200.milliseconds,
        )
        val elector1 = newElector(shortLeaseOptions)
        val elector2 = newElector(shortLeaseOptions)

        // elector1 acquires lock and sleeps beyond leaseTime — TTL expires while sleeping
        val result1 = elector1.runIfLeader(lockName) {
            log.info { "[T5] Leader acquired. Sleeping 500ms (leaseTime=200ms)..." }
            Thread.sleep(500)
            "leader-done"
        }
        log.info { "[T5] elector1 result: $result1" }

        // After elector1 finishes, elector2 attempts acquisition — behavior depends on library contract
        val result2 = elector2.runIfLeader(lockName) { "second-acquired" }
        log.info { "[T5] elector2 result (null=skipped, non-null=acquired): $result2" }
        // No strict assertion — this test documents the library's lease-expiry behavior
    }

    companion object : KLogging()
}

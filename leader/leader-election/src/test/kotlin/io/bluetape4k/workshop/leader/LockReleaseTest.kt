package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T7: Lock release via `finally { unlock }` — immediate re-acquisition test.
 *
 * Verifies that `LettuceLeaderElector.runImpl` releases the lock in its `finally` block
 * immediately after the action completes, even when leaseTime is very long (30s).
 *
 * If unlock did NOT happen, `elector2` would have to wait 30 seconds for TTL expiry.
 * The test would fail via timeout. If it passes quickly, `finally { unlock }` works correctly.
 *
 * NOTE: `connection.close()` does NOT delete the Redis key — do NOT call it expecting key removal.
 */
class LockReleaseTest : AbstractLeaderElectionTest() {

    @Test
    fun `lock is released immediately after action completes, enabling instant re-acquisition`() {
        val lockName = "test:t7:${Base58.randomString(8)}"
        // Long leaseTime — if finally-unlock does NOT run, elector2 would wait 30s
        val longLeaseOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 30.seconds,
        )
        val elector1 = newElector(longLeaseOptions)
        val elector2 = newElector(longLeaseOptions)

        // elector1 acquires, executes no-op, finally-unlock fires immediately
        elector1.runIfLeader(lockName) { /* no-op */ }

        // elector2 can acquire instantly — no 30-second TTL wait
        val result = elector2.runIfLeader(lockName) { "reacquired" }

        result.shouldNotBeNull() shouldBeEqualTo "reacquired"
    }
}

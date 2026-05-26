package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.lock.fenced.FencedResource
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * Smoke test — full stale-holder scenario demonstrating fencing token protection.
 *
 * **Excluded from default CI** via `junit.jupiter.execution.exclude.tags=smoke`
 * (timing-sensitive: relies on 200ms lease expiry + 500ms sleep).
 *
 * Run with: `./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=`
 */
@Tag("smoke")
class FencedStaleHolderTest : AbstractDistributedLockTest() {

    @Test
    fun `stale holder A 의 write 는 fencing token 으로 거부된다`() {
        val lockName = randomName("stale")
        val fLock1 = redisson.getFencedLock(lockName)
        val fLock2 = redisson.getFencedLock(lockName)
        val resource = FencedResource(42L)

        // Step 1: A acquires with a short lease
        val token1 = fLock1.tryLockAndGetToken(1000, 200, MILLISECONDS)
        token1.shouldNotBeNull()

        // Step 2: Wait for A's lease to expire
        Thread.sleep(500)

        // Step 3: B acquires — gets a higher token
        val token2 = fLock2.tryLockAndGetToken(1000, 5000, MILLISECONDS)
        token2.shouldNotBeNull()
        token2 shouldBeGreaterThan token1

        // Step 4: B's write succeeds
        resource.apply(token2) { "B wins" }.shouldNotBeNull()

        // Step 5: A's write is rejected (token1 < token2)
        resource.apply(token1) { "A is stale" }.shouldBeNull()

        // Step 6: A cannot unlock (lease expired)
        assertFailsWith<IllegalMonitorStateException> {
            fLock1.unlock()
        }

        // Step 7: B unlocks normally
        fLock2.unlock()
    }
}

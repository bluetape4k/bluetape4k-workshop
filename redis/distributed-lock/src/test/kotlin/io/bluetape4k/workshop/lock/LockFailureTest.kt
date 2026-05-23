package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.test.assertFailsWith

/**
 * Smoke tests for lock failure scenarios.
 *
 * **Excluded from default CI** via `junit.jupiter.execution.exclude.tags=smoke`
 * because they rely on real-time lease expiry (timing-sensitive).
 *
 * Run with: `./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=`
 */
@Tag("smoke")
class LockFailureTest : AbstractDistributedLockTest() {

    @Test
    fun `lease 만료 후 unlock 시도 시 IllegalMonitorStateException 이 발생한다`() {
        val lockName = randomName("failure")
        val lock = redisson.getLock(lockName)

        val acquired = lock.tryLock(1000, 200, MILLISECONDS)
        acquired.shouldBeTrue()

        Thread.sleep(500)  // wait for lease to expire (200ms lease)

        assertFailsWith<IllegalMonitorStateException> {
            lock.unlock()
        }
    }
}

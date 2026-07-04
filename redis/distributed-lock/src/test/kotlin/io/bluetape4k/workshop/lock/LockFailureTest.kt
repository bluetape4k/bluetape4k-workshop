package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

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

        await atMost Duration.ofSeconds(2) untilAsserted {
            lock.isLocked.shouldBeFalse()
        }

        assertFailsWith<IllegalMonitorStateException> {
            lock.unlock()
        }
    }
}

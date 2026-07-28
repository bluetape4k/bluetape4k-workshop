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
 * lock 실패 시나리오용 smoke test입니다.
 *
 * 실제 시간 기반 lease 만료에 의존해 시간에 민감하므로
 * `junit.jupiter.execution.exclude.tags=smoke` 설정으로 기본 CI에서는 제외합니다.
 *
 * 실행 명령: `./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=`
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

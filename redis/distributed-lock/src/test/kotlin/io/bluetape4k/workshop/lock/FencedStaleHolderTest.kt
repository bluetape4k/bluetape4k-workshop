package io.bluetape4k.workshop.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.lock.fenced.FencedResource
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * fencing token 보호를 보여주는 stale holder 전체 시나리오 smoke test입니다.
 *
 * 200ms lease 만료와 500ms 대기에 의존하는 시간 민감 테스트라서
 * `junit.jupiter.execution.exclude.tags=smoke` 설정으로 기본 CI에서는 제외합니다.
 *
 * 실행 명령: `./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=`
 */
@Tag("smoke")
class FencedStaleHolderTest : AbstractDistributedLockTest() {

    @Test
    fun `stale holder A 의 write 는 fencing token 으로 거부된다`() {
        val lockName = randomName("stale")
        val fLock1 = redisson.getFencedLock(lockName)
        val fLock2 = redisson.getFencedLock(lockName)
        val resource = FencedResource(42L)

        // 1단계: A가 짧은 lease로 lock을 획득합니다.
        val token1 = fLock1.tryLockAndGetToken(1000, 200, MILLISECONDS)
        token1.shouldNotBeNull()

        // 2단계: lock ownership을 건드리지 않고 A의 lease 만료를 기다립니다.
        await atMost Duration.ofSeconds(2) untilAsserted {
            fLock1.isLocked.shouldBeFalse()
        }

        // 3단계: B가 획득하며 더 큰 token을 받습니다.
        val token2 = fLock2.tryLockAndGetToken(1000, 5000, MILLISECONDS)
        token2.shouldNotBeNull()
        token2 shouldBeGreaterThan token1

        // 4단계: B의 쓰기는 성공합니다.
        resource.apply(token2) { "B wins" }.shouldNotBeNull()

        // 5단계: A의 쓰기는 거부됩니다(token1 < token2).
        resource.apply(token1) { "A is stale" }.shouldBeNull()

        // 6단계: A는 lease가 만료되어 unlock할 수 없습니다.
        assertFailsWith<IllegalMonitorStateException> {
            fLock1.unlock()
        }

        // 7단계: B는 정상적으로 unlock합니다.
        fLock2.unlock()
    }
}

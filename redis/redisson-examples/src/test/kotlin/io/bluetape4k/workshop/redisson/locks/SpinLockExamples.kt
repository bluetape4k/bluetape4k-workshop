package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * SpinLock examples
 *
 * Redis based distributed reentrant SpinLock object for Java and implements Lock interface.
 *
 * 짧은 시간 간격으로 수천 개 이상의 잠금을 획득/해제하면 잠금 객체에서 pubsub 사용으로 인해
 * 네트워크 처리량 제한에 도달하고 Redis CPU 과부하가 발생할 수 있습니다.
 * 이는 메시지가 Redis 클러스터의 모든 노드에 분산되는 Redis pubsub의 특성으로 인해 발생합니다.
 * Spin Lock은 기본적으로 pubsub 채널 대신 지수 백오프 전략을 사용하여 잠금을 획득합니다.
 *
 * 잠금을 획득한 Redisson 인스턴스가 충돌하면 해당 잠금은 획득한 상태에서 영원히 멈출 수 있습니다.
 * 이를 방지하기 위해 Redisson은 잠금 감시를 유지하여 잠금 보유자 Redisson 인스턴스가 살아있는 동안 잠금 만료를 연장합니다.
 * 기본적으로 잠금 감시 시간 제한은 30초이며, Config.lockWatchdogTimeout 설정을 통해 변경할 수 있습니다.
 *
 * 참고:
 * - [SpinLock](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers/#89-spin-lock)
 */
class SpinLockExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `basic usage of SpinLock`() {
        val lockName = randomName()
        val lock = redisson.getSpinLock(lockName)
        var locked = false

        // 방법 1: 기본적인 lock 메서드를 사용하여 잠금을 획득합니다.
        // lock.lock()

        // 방법 2: 자동 락 해제 시간 (10초) 저정하여 잠금 획득
        // lock.lock(10, TimeUnit.SECONDS)

        try {
            // 방법 3: 락 획득 대기 시간을 100초 주고, 락을 획득하고, 자동 락 해제 시간을 10초를 지정하는 방식
            locked = lock.tryLock(100, 10, TimeUnit.SECONDS)
            if (locked) {
                log.debug { "lock SpinLock[$lockName]" }
            }
        } finally {
            lock.unlock()
        }
        locked.shouldBeTrue() // lock이 획득되었는지 확인합니다.
    }
}

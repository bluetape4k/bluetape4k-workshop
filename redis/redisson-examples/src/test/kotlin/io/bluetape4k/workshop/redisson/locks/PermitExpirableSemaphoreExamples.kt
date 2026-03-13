package io.bluetape4k.workshop.redisson.locks

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * PermitExpirableSemaphore examples
 *
 * 획득한 각 권한에 대한 임대 시간 매개변수를 지원하는 Java용 Redis 기반 분산 세마포어 객체입니다.
 * 각 권한은 고유 ID로 식별되며 해당 ID를 통해서만 해제할 수 있습니다.
 *
 * 사용 전에 `trySetPermits(permits)` 메서드를 통해 사용 가능한 허가량으로 초기화해야 합니다.
 * `addPermits(permits)` 메서드를 통해 사용 가능한 허가 수를 늘리거나 줄일 수 있습니다.
 *
 * 참고:
 * -[PermitExpirableSemaphore](https://github.com/redisson/redisson/wiki/8.-distributed-locks-and-synchronizers/#87-permitexpirablesemaphore)
 */
class PermitExpirableSemaphoreExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `basic usage for PermitExpirableSemaphore`() {
        val semaphoreName = randomName()
        val semaphore = redisson.getPermitExpirableSemaphore(semaphoreName)

        // 23개 확보
        semaphore.trySetPermits(23)


        // 방법 1 : 기본 방법
        val id1 = semaphore.acquire()

        // 방법 2: 10초 동안 유효한 권한을 획득
        val id2 = semaphore.acquire(10, TimeUnit.SECONDS)

        // 방법 3: 획득 시도
        val id3 = semaphore.tryAcquire()

        // 방법 4: 10초 동안 유효한 권한을 획득 시도
        val id4 = semaphore.tryAcquire(10, TimeUnit.SECONDS)

        // 방법 5: 획득 대기 시간을 10초 주고, 15초 동안 유효한 권한을 획득 시도
        val id5 = semaphore.tryAcquire(10, 15, TimeUnit.SECONDS)

        if (id5 != null) {
            log.debug { "Semaphore id5=$id5" }
            try {
                Thread.sleep(100)
            } finally {
                semaphore.release(id5)
            }
        }
    }

    @Test
    fun `basic async usage for PermitExpirableSemaphore`() = runTest {
        val semaphoreName = randomName()
        val semaphore = redisson.getPermitExpirableSemaphore(semaphoreName)

        // 23개 확보
        semaphore.trySetPermitsAsync(23).await()


        // 방법 1 : 기본 방법
        val id1 = semaphore.acquireAsync().await()

        // 방법 2: 10초 동안 유효한 권한을 획득
        val id2 = semaphore.acquireAsync(10, TimeUnit.SECONDS).await()

        // 방법 3: 획득 시도
        val id3 = semaphore.tryAcquireAsync().await()

        // 방법 4: 10초 동안 유효한 권한을 획득 시도
        val id4 = semaphore.tryAcquireAsync(10, TimeUnit.SECONDS).await()

        // 방법 5: 획득 대기 시간을 10초 주고, 15초 동안 유효한 권한을 획득 시도
        val id5 = semaphore.tryAcquireAsync(10, 15, TimeUnit.SECONDS).await()

        if (id5 != null) {
            log.debug { "Semaphore id5=$id5" }
            delay(100)
            semaphore.release(id5)
        }
    }
}

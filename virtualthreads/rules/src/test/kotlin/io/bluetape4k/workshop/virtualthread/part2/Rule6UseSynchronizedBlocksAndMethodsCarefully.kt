package io.bluetape4k.workshop.virtualthread.part2

import io.bluetape4k.logging.KLogging
import kotlinx.atomicfu.locks.withLock
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.locks.ReentrantLock

/**
 * Rule 6: Virtual Thread 사용 시에는 `synchronized` 블록을 사용하지 말고, 명시적으로 [ReentrantLock] 사용하세요
 */
class Rule6UseSynchronizedBlocksAndMethodsCarefully {

    companion object: KLogging()

    @Nested
    inner class DoNot {

        private val lockObj = Any()

        @Test
        fun `비추천 - 리소스를 독점적으로 사용할 목적으로 synchronized 사용하기`() {
            synchronized(lockObj) {
                exclusiveResource()
            }
        }
    }

    @Nested
    inner class Do {

        private val lock = ReentrantLock()

        @Test
        fun `추천 - 리소스를 독점적으로 사용할 때 ReentrantLock을 이용`() {
            lock.lock()
            try {
                // critical section
                exclusiveResource()
            } finally {
                lock.unlock()
            }
        }

        @Test
        fun `추천 - 리소스를 독점적으로 사용할 때 ReentrantLock을 이용하세요 - Kotlin withLock 함수`() {
            lock.withLock {
                exclusiveResource()
            }
        }
    }

    private fun exclusiveResource(): String {
        Thread.sleep(100)
        return "result"
    }
}

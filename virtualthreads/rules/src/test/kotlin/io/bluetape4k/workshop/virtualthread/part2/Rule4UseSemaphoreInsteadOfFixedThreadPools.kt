package io.bluetape4k.workshop.virtualthread.part2

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore

/**
 * Rule 4: 동시성을 제어할 때에는 FixedThreadPool 을 사용하지 말고, [Semaphore]를 사용하세요
 */
class Rule4UseSemaphoreInsteadOfFixedThreadPools {

    companion object: KLoggingChannel()

    @Nested
    inner class DoNot {

        private val executor = Executors.newFixedThreadPool(8)

        @Test
        fun `비추천 - FixedThreadPool을 사용하여 동시성 제어하기`() {
            val results = ConcurrentLinkedQueue<Future<String>>()

            Executors.newFixedThreadPool(8, Thread.ofVirtual().factory()).use { executor ->
                val futures = List(100) { index ->
                    executor.submit {
                        log.debug { "Start run task[$index]" }
                        val result = executor.submit<String> { sharedResource() }
                        log.debug { "Finish run task[$index]" }
                        results.add(result)
                    }
                }
                futures.forEach { it.get() }
                results.size shouldBeEqualTo 100
            }
        }
    }

    @Nested
    inner class Do {
        private val semaphore = Semaphore(8)

        private fun useSemaphoreToLimitConcurrency(): String {
            semaphore.acquire()
            return try {
                sharedResource()
            } finally {
                semaphore.release()
            }
        }

        @Test
        fun `추천 - Semaphore를 이용하여 동시성 제어하기`() {
            val results = ConcurrentLinkedQueue<String>()

            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val futures = List(100) { index ->
                    executor.submit {
                        log.debug { "Start run task[$index]" }
                        val result = useSemaphoreToLimitConcurrency()
                        log.debug { "Finish run task[$index]" }
                        results.add(result)
                    }
                }
                futures.forEach { it.get() }
                results.size shouldBeEqualTo 100
            }
        }
    }

    private fun sharedResource(): String {
        Thread.sleep(100)
        return "result"
    }
}

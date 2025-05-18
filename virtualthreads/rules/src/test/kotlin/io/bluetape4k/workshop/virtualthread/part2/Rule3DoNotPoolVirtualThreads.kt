package io.bluetape4k.workshop.virtualthread.part2

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * Rule 3: Virtual Thread 를 Pool 에 넣지 마세요.
 *
 * ThreadPool 사용 시 Virtual Thread Factory 를 사용하지 마세요.
 * Virtual Thread의 장점이 사라지고, Thread Pool Size 에 재한되어 버립니다.
 *
 * 즉, Virtual Thread 생성 수가 Platform Thread의 수에 의해 제한될 수 있습니다.
 * 또한 작업들이 Virtual Thread 를 재활용하도록 한다. (이건 Virtual Thread에는 장점이 아니다.)
 */
class Rule3DoNotPoolVirtualThreads {

    companion object: KLoggingChannel()

    @Nested
    inner class DoNot {
        @Test
        fun `비추천 - ThreadPool 에서 Virtual Thread 생성하기`() {

            Executors.newCachedThreadPool(Thread.ofVirtual().factory()).use { executor ->
                executor.javaClass.name shouldBeEqualTo "java.util.concurrent.ThreadPoolExecutor"

                executor.submit {
                    Thread.sleep(1000)
                    log.debug { "1 run ${Thread.currentThread()}" }
                }

                executor.submit {
                    Thread.sleep(1000)
                    log.debug { "2 run ${Thread.currentThread()}" }
                }
            }
        }
    }

    @Nested
    inner class Do {

        @Test
        fun `추천 - ThreadPerTaskExecutor 로 Virtual Thread 생성하기`() {
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory()).use { executor ->
                executor.javaClass.name shouldBeEqualTo "java.util.concurrent.ThreadPerTaskExecutor"

                executor.submit {
                    Thread.sleep(1000)
                    log.debug { "1 run ${Thread.currentThread()}" }
                }

                executor.submit {
                    Thread.sleep(1000)
                    log.debug { "2 run ${Thread.currentThread()}" }
                }
            }
        }
    }
}

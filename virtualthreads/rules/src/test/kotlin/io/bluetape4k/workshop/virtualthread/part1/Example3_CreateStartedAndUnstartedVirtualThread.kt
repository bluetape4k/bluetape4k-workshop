package io.bluetape4k.workshop.virtualthread.part1

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualThreads.AbstractVirtualThreadTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Example3_CreateStartedAndUnstartedVirtualThread: AbstractVirtualThreadTest() {

    companion object: KLoggingChannel()

    @Test
    fun `자동 시작하는 Virtual Thread 생성하기`() {
        val builder = Thread.ofVirtual()
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)

        val thread = builder.start {
            started.countDown()
            allowFinish.await()
            log.debug { "Virtual thread running" }
        }
        try {
            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            thread.isAlive.shouldBeTrue()
        } finally {
            allowFinish.countDown()
            thread.join()
        }
        thread.state shouldBeEqualTo Thread.State.TERMINATED
    }

    @Test
    fun `수동으로 시작하는 Virtual Thread 생성하기`() {
        val builder = Thread.ofVirtual()
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)

        val thread = builder.unstarted {
            started.countDown()
            allowFinish.await()
            log.debug { "Virtual thread running" }
        }
        thread.state shouldBeEqualTo Thread.State.NEW
        thread.start()

        try {
            started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            thread.isAlive.shouldBeTrue()
        } finally {
            allowFinish.countDown()
            thread.join()
        }
        thread.state shouldBeEqualTo Thread.State.TERMINATED
    }
}

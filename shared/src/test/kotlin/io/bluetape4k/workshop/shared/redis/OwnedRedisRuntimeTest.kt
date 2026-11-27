package io.bluetape4k.workshop.shared.redis

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OwnedRedisRuntimeTest {

    @Test
    fun `start is idempotent and close stops a started handle only once`() {
        val handle = RecordingHandle()
        val runtime = OwnedRedisRuntime { handle }

        runtime.start() shouldBeEqualTo handle.endpoint
        runtime.start() shouldBeEqualTo handle.endpoint
        handle.starts shouldBeEqualTo 1
        runtime.isRunning.shouldBeTrue()

        runtime.close()
        runtime.close()

        handle.stops shouldBeEqualTo 1
        runtime.isClosed.shouldBeTrue()
    }

    @Test
    fun `failed start rolls back a partially started handle`() {
        val handle = RecordingHandle(startFailure = IllegalStateException("readiness failed"))
        val runtime = OwnedRedisRuntime { handle }

        assertThrows<IllegalStateException> { runtime.start() }

        handle.starts shouldBeEqualTo 1
        handle.stops shouldBeEqualTo 1
        runtime.isClosed.shouldBeTrue()
    }

    @Test
    fun `close restores interrupt status when a handle stop is interrupted`() {
        val handle = RecordingHandle(stopFailure = InterruptedException("interrupted"))
        val runtime = OwnedRedisRuntime { handle }
        runtime.start()

        try {
            assertThrows<InterruptedException> { runtime.close() }
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `endpoint properties use the existing testcontainers namespace`() {
        val runtime = OwnedRedisRuntime { RecordingHandle() }
        val registration = runtime.registerSystemProperties()
        try {
            System.getProperty("testcontainers.redis.host") shouldBeEqualTo "localhost"
            System.getProperty("testcontainers.redis.port") shouldBeEqualTo "6379"
            System.getProperty("testcontainers.redis.url") shouldBeEqualTo "redis://localhost:6379"
        } finally {
            registration.close()
            runtime.close()
        }
    }

    private class RecordingHandle(
        private val startFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
    ): RedisContainerHandle {
        val endpoint = RedisEndpoint("localhost", 6379, "redis://localhost:6379")
        var starts: Int = 0
        var stops: Int = 0

        override fun start() {
            starts++
            startFailure?.let { throw it }
        }

        override fun stop() {
            stops++
            stopFailure?.let { throw it }
        }

        override fun endpoint(): RedisEndpoint = endpoint
    }
}

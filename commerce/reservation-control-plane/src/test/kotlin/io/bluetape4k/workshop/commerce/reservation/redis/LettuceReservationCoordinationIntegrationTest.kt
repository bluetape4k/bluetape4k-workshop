package io.bluetape4k.workshop.commerce.reservation.redis

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*

class LettuceReservationCoordinationIntegrationTest {
    @Test
    fun `published Lettuce semaphore bounds advisory admission and recovers after release`() =
        withRedisConnection { connection ->
            val key = "reservation:test:semaphore:${UUID.randomUUID()}"
            val first = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)
            val second = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)

            try {
                first.tryAcquire(Duration.ZERO).shouldBeTrue()
                second.tryAcquire(Duration.ZERO).shouldBeFalse()
                first.release()
                second.tryAcquire(200.millis()).shouldBeTrue()
                second.release()
            } finally {
                connection.sync().del(key)
            }
        }

    @Test
    fun `published Lettuce lock suppresses the same opaque command until token checked release`() =
        withRedisConnection { connection ->
            val prefix = "reservation:test:suppression:${UUID.randomUUID()}"
            val backend = LettuceLockSuppressionBackend(connection, prefix, 2.seconds())

            val first = backend.tryAcquire("opaque_id_123")
            first?.let {
                backend.tryAcquire("opaque_id_123").shouldBeNull()
                it.close()
                backend.tryAcquire("opaque_id_123")?.close()
            }
            (first != null).shouldBeTrue()
            connection.sync().del("$prefix:opaque_id_123")
        }

    @Test
    fun `semaphore reinitializes after its advisory Redis key is evicted`() =
        withRedisConnection { connection ->
            val key = "reservation:test:eviction:${UUID.randomUUID()}"
            val beforeEviction = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)
            val afterEviction = LettuceSemaphoreAdmissionBackend(connection, key, totalPermits = 1)

            beforeEviction.tryAcquire(Duration.ZERO).shouldBeTrue()
            connection.sync().del(key)
            afterEviction.tryAcquire(Duration.ZERO).shouldBeTrue()
            afterEviction.release()
            connection.sync().del(key)
        }

    private fun withRedisConnection(block: (io.lettuce.core.api.StatefulRedisConnection<String, String>) -> Unit) {
        val redis = RedisServer.Launcher.redis
        val client = LettuceClients.clientOf(redis.url)
        val connection = LettuceClients.connect(client)
        try {
            block(connection)
        } finally {
            connection.close()
            LettuceClients.shutdown(client)
        }
    }
}

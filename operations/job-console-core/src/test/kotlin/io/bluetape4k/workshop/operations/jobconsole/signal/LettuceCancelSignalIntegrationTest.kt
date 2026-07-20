package io.bluetape4k.workshop.operations.jobconsole.signal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.pubsub.RedisPubSubAdapter
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Tag("integration")
class LettuceCancelSignalIntegrationTest {

    @Test
    fun `publish wakes a subscribed worker without becoming state authority`() {
        val client = RedisClient.create("redis://${redis.host}:${redis.port}")
        client.connectPubSub().use { subscriber ->
            val messages = LinkedBlockingQueue<String>()
            subscriber.addListener(
                object : RedisPubSubAdapter<String, String>() {
                    override fun message(channel: String, message: String) {
                        messages.offer(message)
                    }
                },
            )
            subscriber.sync().subscribe(LettuceCancelSignal.CHANNEL)
            LettuceCancelSignal("redis://${redis.host}:${redis.port}").use { signal ->
                val jobId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")

                signal.publish(jobId).delivered shouldBeEqualTo true
                messages.poll(2, TimeUnit.SECONDS) shouldBeEqualTo jobId.toString()
            }
        }
        client.shutdown()
    }

    companion object {
        private val redis: RedisServer by lazy { RedisServer.Launcher.redis }
    }
}

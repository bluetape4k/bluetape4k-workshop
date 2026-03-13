package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.future.await
import org.amshove.kluent.shouldBeEqualTo
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import org.redisson.api.RReliableTopic
import org.redisson.api.RTopic
import java.util.concurrent.atomic.AtomicInteger

/**
 * Topic examples
 *
 * 참고:
 * - [Topic](https://github.com/redisson/redisson/wiki/6.-distributed-objects#67-topic)
 * - [Topic pattern](https://github.com/redisson/redisson/wiki/6.-distributed-objects#671-topic-pattern)
 * - [Shared topic](https://github.com/redisson/redisson/wiki/6.-distributed-objects#672-sharded-topic)
 */
class TopicExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `add topic listener - pub & sub`() = runSuspendIO {
        val topic: RTopic = redisson.getTopic(randomName())
        val receivedCounter = AtomicInteger(0)

        // topic 예 listener를 등록합니다.
        // listener id 를 반환한다.
        val listenerId1 = topic.addListenerAsync(String::class.java) { channel, msg ->
            println("Listener1: channel[$channel] received: $msg")
            receivedCounter.incrementAndGet()
        }.await()

        // topic 예 listener를 등록합니다.
        val listenerId2 = topic.addListenerAsync(String::class.java) { channel, msg ->
            println("Listener2: channel[$channel] received: $msg")
            receivedCounter.incrementAndGet()
        }.await()

        log.debug { "Listener listener1 Id=$listenerId1, listener2 Id=$listenerId2" }
        topic.countListeners() shouldBeEqualTo 2    // Listener 는 2개 등록 
        topic.countSubscribers() shouldBeEqualTo 1  // 단순 RedissonTopic 은 Subscriber 가 1개

        // topic 에 메시지 전송
        topic.publishAsync("message-1").await()
        topic.publishAsync("message-2").await()

        // topic 에 listener가 2개, 메시지 2개 전송
        await until { receivedCounter.get() >= 2 * 2 }

        topic.removeAllListenersAsync().await()
    }

    /**
     * 복수의 Redisson Connection에 대해서 Reliable Topic 을 이용하여 메시지를 전달한다.
     */
    @Test
    fun `using reliable topic`() = runSuspendIO {
        val channelName = randomName()

        val redisson1 = newRedisson()
        val topic1: RReliableTopic = redisson1.getReliableTopic(channelName)

        val redisson2 = newRedisson()
        val topic2 = redisson2.getReliableTopic(channelName)

        val receivedCounter = AtomicInteger(0)

        // topic 예 listener를 등록합니다.
        // listener id 를 반환한다.
        val listenerId1 = topic1.addListenerAsync(String::class.java) { channel, msg ->
            println("Listener1: channel[$channel] received: $msg")
            receivedCounter.incrementAndGet()
        }.await()

        // topic 예 listener를 등록합니다.
        val listenerId2 = topic2.addListenerAsync(String::class.java) { channel, msg ->
            println("Listener2: channel[$channel] received: $msg")
            receivedCounter.incrementAndGet()
        }.await()

        log.debug { "Listener listener1 Id=$listenerId1, listener2 Id=$listenerId2" }
        topic1.countListeners() shouldBeEqualTo 1    // topic1에 Listener 는 1개 등록
        topic1.countSubscribers() shouldBeEqualTo 2  // 2개의 topic 이므로
        topic2.countListeners() shouldBeEqualTo 1    // topic2에 Listener 는 1개 등록
        topic2.countSubscribers() shouldBeEqualTo 2  // 2개의 topic 이므로

        // topic 에 메시지 전송
        topic1.publishAsync("message-1").await()
        topic2.publishAsync("message-2").await()

        // topic 에 listener가 2개, 메시지 2개 전송
        await until { receivedCounter.get() >= 2 * 2 }

        topic1.removeAllListenersAsync().await()
        topic2.removeAllListenersAsync().await()

        redisson1.shutdown()
        redisson2.shutdown()
    }
}

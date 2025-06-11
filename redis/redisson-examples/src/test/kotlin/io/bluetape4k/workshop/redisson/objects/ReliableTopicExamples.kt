package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.coroutines.support.suspendAwait
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test

/**
 * Reliable topic examples
 *
 * Redis 기반 RReliableTopic 객체의 Java 구현은 안정적인 메시지 전달을 통해 게시/구독 메커니즘을 구현합니다.
 * Redis 연결이 중단된 경우 놓친 모든 메시지는 Redis에 재접속한 후에 전달됩니다.
 * 메시지가 Redisson에 의해 수신되고 토픽 리스너에 의해 처리를 위해 제출되었을 때 전달된 것으로 간주됩니다.
 *
 * 각 RReliableTopic 객체 인스턴스(구독자)에는 첫 번째 리스너가 등록될 때 시작되는 자체 워치독이 있습니다.
 * 워치독이 다음 타임아웃 시간 간격까지 연장하지 않으면 구독자는 org.redisson.config.Config#reliableTopicWatchdogTimeout 시간 초과 후 만료됩니다.
 * 이렇게 하면 레디슨 클라이언트 충돌 또는 기타 이유로 인해 구독자가 메시지를 소비할 수 없을 때 토픽에 저장된 메시지가 무한대로 증가하는 것을 방지할 수 있습니다.
 *
 * 토픽 리스너는 Redis에 다시 연결하거나 Redis 장애 조치 후 자동으로 다시 구독됩니다.
 *
 * 참고:
 * - [Reliable Topic](https://github.com/redisson/redisson/wiki/6.-distributed-objects#613-reliable-topic)
 */
class ReliableTopicExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `자동 재구독이 되는 Topic`() = runTest {
        val topicName = randomName()
        val topic = redisson.getReliableTopic(topicName)
        val listenCounter = atomic(0)

        topic.addListener(String::class.java) { channel, msg ->
            log.debug { "Listener: channel[$channel] received: $msg" }
            listenCounter.incrementAndGet()
        }

        val job = launch {
            val topic2 = redisson.getReliableTopic(topicName)
            topic2.publishAsync("Message-${randomString()}").suspendAwait()
        }
        yield()
        job.join()

        await until { listenCounter.value >= 1 }
    }
}

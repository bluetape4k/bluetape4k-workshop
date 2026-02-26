package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.coroutines.support.awaitSuspending
import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.streamAddArgsOf
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.junit.jupiter.api.Test
import org.redisson.api.RStream
import org.redisson.api.stream.StreamAddArgs
import org.redisson.api.stream.StreamCreateGroupArgs
import org.redisson.api.stream.StreamMessageId
import org.redisson.api.stream.StreamReadGroupArgs
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * [RStream] Examples
 *
 * 참고:
 * - [Redis Stream for Java](https://redisson.org/articles/redis-streams-for-java.html)
 * - [Redisson Stream](https://github.com/redisson/redisson/wiki/7.-distributed-collections/#720-stream)
 */
class StreamExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel()

    @Test
    fun `stream 기본 사용 예`() {
        val groupName = "testGroup-" + TimebasedUuid.Reordered.nextIdAsString()

        val stream = redisson.getStream<String, String>(randomName())

        // Consumer group 을 만든다
        stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream())

        val id1 = stream.add(StreamAddArgs.entry("key1", "value1"))
        log.debug { "메시지 전송, messageId1=$id1" }
        val id2 = stream.add(StreamAddArgs.entry("key2", "value2"))
        log.debug { "메시지 전송, messageId2=$id2" }

        val group = stream.readGroup(
            groupName,
            "consumer1",
            StreamReadGroupArgs.neverDelivered()
        )
        group.forEach { (id, map) ->
            log.debug { "Read group. id=$id, map=$map" }
        }

        // return entries in pending state after read group method execution
        val pendingData = stream.pendingRange(
            groupName,
            "consumer1",
            StreamMessageId.MIN,
            StreamMessageId.MAX,
            100
        )
        pendingData.forEach { (id, map) ->
            log.debug { "Pending data. id=$id, map=$map" }
        }

        // transfer ownership of pending messages to a new consumer
        val transferedIds = stream.fastClaim(
            groupName,
            "consumer2",
            1,
            TimeUnit.MILLISECONDS,
            id1,
            id2
        )
        transferedIds.forEach { id ->
            log.debug { "Transfered id=$id" }
        }

        // mark pending entries as correctly processed
        val amount = stream.ack(groupName, id1, id2)

        amount shouldBeEqualTo 2L
    }

    @Test
    fun `stream usage`() = runSuspendIO {
        val groupName = "group-" + TimebasedUuid.Reordered.nextIdAsString()
        val consumerName1 = "consumer-" + TimebasedUuid.Reordered.nextIdAsString()
        val consumerName2 = "consumer-" + TimebasedUuid.Reordered.nextIdAsString()

        val stream: RStream<String, Int> = redisson.getStream(randomName())

        // Consumer group 을 만든다. Stream이 없다면 새로 만든다 
        stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream())

        // 메시지를 전송한다
        val messageId1 = stream.addAsync(StreamAddArgs.entry("1", 1)).awaitSuspending()
        val messageId2 = stream.addAsync(StreamAddArgs.entry("2", 2)).awaitSuspending()
        log.debug { "메시지 전송, messageId1=$messageId1" }
        log.debug { "메시지 전송, messageId2=$messageId2" }

        // 2개의 메시지를 받는다
        val map1 = stream.readGroupAsync(
            groupName,
            consumerName1,
            StreamReadGroupArgs.neverDelivered()
        ).awaitSuspending()

        map1.keys.forEach { messageId ->
            log.debug { "메시지 수신, messageId=$messageId" }
        }

        map1.keys shouldHaveSize 2
        map1.keys shouldBeEqualTo setOf(messageId1, messageId2)

        // 2개의 메시지를 읽었다고 ack 보냄 (전송완료)
        stream.ackAsync(groupName, *map1.keys.toTypedArray()).awaitSuspending()

        // 메시지를 기다린다.
        val consumerJob = scope.launch {
            // 1개의 메시지를 받는다
            val map2: Map<StreamMessageId, Map<String, Int>?> = stream.readGroupAsync(
                groupName,
                consumerName2,
                StreamReadGroupArgs.neverDelivered().timeout(10.seconds.toJavaDuration())
            ).awaitSuspending()

            // 1개의 메시지를 받았다
            map2.keys shouldHaveSize 1
            val msgId = map2.keys.first()
            log.debug { "메시지 수신, messageId=$msgId" }
            map2[msgId]!! shouldBeEqualTo mapOf("3" to 3, "4" to 4)

            stream.ackAsync(groupName, *map2.keys.toTypedArray()).awaitSuspending() shouldBeEqualTo 1L
        }

        // 새로운 메시지 1개를 전송한다
        val messageId3 = stream.addAsync(streamAddArgsOf("3" to 3, "4" to 4)).awaitSuspending()
        log.debug { "메시지 전송, messageId3=$messageId3" }
        delay(10)
        consumerJob.join()

        stream.deleteAsync().awaitSuspending()
    }
}

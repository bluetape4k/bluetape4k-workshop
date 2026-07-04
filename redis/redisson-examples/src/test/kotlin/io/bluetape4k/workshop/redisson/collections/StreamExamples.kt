package io.bluetape4k.workshop.redisson.collections

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.redis.redisson.streamAddArgsOf
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
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
class StreamExamples : AbstractRedissonTest() {

    companion object : KLoggingChannel()

    @Test
    fun `stream 기본 사용 예`() {
        val groupName = "testGroup-" + Uuid.V7.nextIdAsString()

        val stream = redisson.getStream<String, String>(randomName())

        // Create a consumer group.
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
        val groupName = "group-" + Uuid.V7.nextIdAsString()
        val consumerName1 = "consumer-" + Uuid.V7.nextIdAsString()
        val consumerName2 = "consumer-" + Uuid.V7.nextIdAsString()

        val stream: RStream<String, Int> = redisson.getStream(randomName())

        // Create a consumer group and create the stream if it does not exist.
        stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream())

        // Publish messages.
        val messageId1 = stream.addAsync(StreamAddArgs.entry("1", 1)).await()
        val messageId2 = stream.addAsync(StreamAddArgs.entry("2", 2)).await()
        log.debug { "메시지 전송, messageId1=$messageId1" }
        log.debug { "메시지 전송, messageId2=$messageId2" }

        // Read two messages.
        val map1 = stream.readGroupAsync(
            groupName,
            consumerName1,
            StreamReadGroupArgs.neverDelivered()
        ).await()

        map1.keys.forEach { messageId ->
            log.debug { "메시지 수신, messageId=$messageId" }
        }

        map1.keys shouldHaveSize 2
        map1.keys shouldBeEqualTo setOf(messageId1, messageId2)

        // Ack two processed messages.
        stream.ackAsync(groupName, *map1.keys.toTypedArray()).await()

        // Wait for the next message.
        val consumerJob = scope.launch {
            // Read one message.
            val map2: Map<StreamMessageId, Map<String, Int>?> = stream.readGroupAsync(
                groupName,
                consumerName2,
                StreamReadGroupArgs.neverDelivered().timeout(10.seconds.toJavaDuration())
            ).await()

            // One message was received.
            map2.keys shouldHaveSize 1
            val msgId = map2.keys.first()
            log.debug { "메시지 수신, messageId=$msgId" }
            map2[msgId].shouldNotBeNull() shouldBeEqualTo mapOf("3" to 3, "4" to 4)

            stream.ackAsync(groupName, *map2.keys.toTypedArray()).await() shouldBeEqualTo 1L
        }

        // Publish one new message.
        val messageId3 = stream.addAsync(streamAddArgsOf("3" to 3, "4" to 4)).await()
        log.debug { "메시지 전송, messageId3=$messageId3" }
        delay(10)
        consumerJob.join()

        stream.deleteAsync().await()
    }
}

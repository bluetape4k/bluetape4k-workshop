package io.bluetape4k.workshop.redisson.objects

import io.bluetape4k.coroutines.support.awaitSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.redisson.AbstractRedissonTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.RepeatedTest
import org.redisson.api.RBinaryStream
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * [RBinaryStream] 예제
 *
 * Java implementation of Redis based RBinaryStream object holds sequence of bytes.
 * It extends RBucket interface and size is limited to 512Mb.
 *
 * 참고:
 * - [Binary Stream Holder](https://github.com/redisson/redisson/wiki/6.-distributed-objects/#62-binary-stream-holder)
 */
class BinaryStreamExamples: AbstractRedissonTest() {

    companion object: KLoggingChannel() {
        private const val REPEAT_SIZE = 3
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `RBinaryStream 사용 예`() = runSuspendIO {
        val stream: RBinaryStream = redisson.getBinaryStream(randomName())

        val contentStr = randomString()
        val contentBytes = contentStr.toUtf8Bytes()

        // 값이 없으면 설정하고, TTL 을 10초로 준다 
        stream.setIfAbsentAsync(contentBytes, 10.seconds.toJavaDuration()).awaitSuspending().shouldBeTrue()
        // 값을 다시 설정한다
        stream.setAsync(contentBytes).awaitSuspending()

        // 입력 스트림을 읽는다
        val loadedBytes = stream.inputStream.readBytes()
        val loadedStr = loadedBytes.toUtf8String()
        loadedStr shouldBeEqualTo contentStr

        // 기존 값을 비교해서 새로운 Bytes 로 대체한다
        val contentBytes2 = randomString().toUtf8Bytes()

        stream.compareAndSetAsync(contentBytes, contentBytes2).awaitSuspending().shouldBeTrue()

        // 대체된 값을 확인한다
        stream.inputStream.readBytes() shouldBeEqualTo contentBytes2

        stream.deleteAsync().awaitSuspending().shouldBeTrue()
    }
}

package io.bluetape4k.okio

import io.bluetape4k.io.okio.bufferOf
import io.bluetape4k.io.okio.buffered
import io.bluetape4k.junit5.concurrency.TestingExecutors
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.junit5.system.assumeNotWindows
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.HashingSink
import okio.Pipe
import org.amshove.kluent.internal.assertFailsWith
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.InterruptedIOException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class PipeTest: AbstractOkioTest() {

    companion object: KLogging()

    private lateinit var executor: ScheduledExecutorService

    @BeforeEach
    fun beforeEach() {
        this.executor = TestingExecutors.newScheduledExecutorService(2)
    }

    @AfterEach
    fun afterEach() {
        executor.shutdown()
    }

    @Test
    fun `pipe with buffer`() {
        val pipe = Pipe(6)

        // Write to sink `abc`
        pipe.sink.write(bufferOf("abc"), 3L)

        val source = pipe.source
        val readBuffer = Buffer()

        source.read(readBuffer, 6L)
        readBuffer.readUtf8() shouldBeEqualTo "abc"

        pipe.sink.close()

        // sink 가 close 되었으므로, 읽어오지 못한다
        source.read(readBuffer, 6L) shouldBeEqualTo -1L
        source.close()
    }

    /**
     * 프로듀서는 `Random(0)`으로 생성된 바이트의 처음 16 MiB를 싱크에 쓰고, 컨슈머가 소비한다.
     * 프로듀서는 소비자가 모든 데이터를 소비할 때까지 기다린다.
     */
    @Test
    fun `large dataset`() {
        val pipe = Pipe(1000L)  // An awkward size to force producer/consumer exchange
        val totalBytes = 16 * 1024L * 1024L  // 16 MiB
        val expectedHash = "4e90583a19f57e0d3ca0346617a91308fe157f30".decodeHex()
        log.debug { "Expected hash=${expectedHash.hex()}" }

        // Write data to the sink
        val sinkHash = executor.submit<ByteString> {
            val hashingSink = HashingSink.sha1(pipe.sink)
            val random = Random(0)
            val data = ByteArray(8192)
            val buffer = Buffer()
            var i = 0L
            while (i < totalBytes) {
                random.nextBytes(data)
                buffer.write(data)
                hashingSink.write(buffer, buffer.size) // buffer 정보를 hashingSink에 씀
                i += data.size.toLong()
                log.debug { "Wrote $i bytes to sink" }
            }
            hashingSink.close()
            hashingSink.hash
        }

        // Read data from source
        val sourceHash = executor.submit<ByteString> {
            val blackhole = Buffer()
            val hashingSink = HashingSink.sha1(blackhole)
            val buffer = Buffer()
            while (pipe.source.read(buffer, Long.MAX_VALUE) != -1L) {
                log.debug { "Read ${buffer.size} bytes from source" }
                hashingSink.write(buffer, buffer.size)
                blackhole.clear()
            }
            pipe.source.close()
            hashingSink.hash
        }

        sinkHash.get() shouldBeEqualTo expectedHash
        sourceHash.get() shouldBeEqualTo expectedHash
    }

    @Test
    fun `large dataset in coroutines`() = runSuspendIO {
        val pipe = Pipe(1000L)  // An awkward size to force producer/consumer exchange
        val totalBytes = 16 * 1024L * 1024L  // 16 MiB
        val expectedHash = "4e90583a19f57e0d3ca0346617a91308fe157f30".decodeHex()
        log.debug { "Expected hash=${expectedHash.hex()}" }

        // Write data to the sink
        val sinkHash = async {
            val hashingSink = HashingSink.sha1(pipe.sink)
            val random = Random(0)
            val data = ByteArray(8192)
            val buffer = Buffer()
            var i = 0L
            while (i < totalBytes) {
                random.nextBytes(data)
                buffer.write(data)
                hashingSink.write(buffer, buffer.size) // buffer 정보를 hashingSink에 씀
                i += data.size.toLong()
                log.debug { "Wrote $i bytes to sink" }
            }
            hashingSink.close()
            hashingSink.hash
        }

        // Read data from source
        val sourceHash = async {
            val blackhole = Buffer()
            val hashingSink = HashingSink.sha1(blackhole)
            val buffer = Buffer()
            while (pipe.source.read(buffer, Long.MAX_VALUE) != -1L) {
                log.debug { "Read ${buffer.size} bytes from source" }
                hashingSink.write(buffer, buffer.size)
                blackhole.clear()
            }
            pipe.source.close()
            hashingSink.hash
        }

        sinkHash.await() shouldBeEqualTo expectedHash
        sourceHash.await() shouldBeEqualTo expectedHash
    }

    @Test
    fun `sink with timeout`() {
        assumeNotWindows()

        val pipe = Pipe(3)
        pipe.sink.timeout().timeout(1000L, TimeUnit.MILLISECONDS)
        pipe.sink.write(bufferOf("abc"), 3L)

        // Pipe 버퍼 크기가 3이고, sink timeout 이 1초라면,
        // 1초 내에 읽어가지 않고, sink 에 추가로 쓰려고 하면, timeout 이 발생한다.
        val start = now()
        try {
            pipe.sink.write(bufferOf("def"), 3L)
            fail("여기가 실행되면 안됩니다. sink timeout 이 발생해야 합니다.")
        } catch (expected: InterruptedIOException) {
            expected.message shouldBeEqualTo "timeout"
            val elapsed = now() - start
            log.warn(expected) { "Sink timeout occurred after $elapsed ms" }
        }

        assertElapsed(1000.0, start)

        // 이미 타임아웃으로 `def`를 쓸 수 없으므로, `abc`만 읽을 수 있다.
        val readBuffer = Buffer()
        pipe.source.read(readBuffer, 6L) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "abc"
    }

    @Test
    fun `source with timeout`() {
        assumeNotWindows()

        val pipe = Pipe(3)
        pipe.source.timeout().timeout(1000L, TimeUnit.MILLISECONDS)

        val start = now()
        val readBuffer = Buffer()
        try {
            // timeout 1초 이내에 읽어가지 않으면, timeout 이 발생한다.
            pipe.source.read(readBuffer, 6L)
            fail("여기가 실행되면 안됩니다. source timeout 이 발생해야 합니다.")
        } catch (expected: InterruptedIOException) {
            expected.message shouldBeEqualTo "timeout"
            val elapsed = now() - start
            log.warn(expected) { "Source timeout occurred after $elapsed ms" }
        }

        assertElapsed(1000.0, start)
        readBuffer.size shouldBeEqualTo 0L
    }

    /**
     * The writer is writing 12 bytes as fast as it can to a 3 byte buffer. The reader alternates
     * sleeping 1000 ms, then reading 3 bytes. That should make for an approximate timeline like
     * this:
     *
     * ```
     *    0: writer writes 'abc', blocks 0: reader sleeps until 1000
     * 1000: reader reads 'abc', sleeps until 2000
     * 1000: writer writes 'def', blocks
     * 2000: reader reads 'def', sleeps until 3000
     * 2000: writer writes 'ghi', blocks
     * 3000: reader reads 'ghi', sleeps until 4000
     * 3000: writer writes 'jkl', returns
     * 4000: reader reads 'jkl', returns
     * ```
     *
     *
     * Because the writer is writing to a buffer, it finishes before the reader does.
     */
    @Test
    fun `sink blocks on slow reader`() {
        val pipe = Pipe(3L)
        val task = executor.submit<Unit> {
            val buffer = Buffer()
            Thread.sleep(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "abc"

            Thread.sleep(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "def"

            Thread.sleep(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "ghi"

            Thread.sleep(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "jkl"
        }

        val start = now()
        pipe.sink.write(bufferOf("abcdefghijkl"), 12L)
        assertElapsed(3000.0, start)
        task.get(5, TimeUnit.SECONDS) // Wait for the task to complete
    }

    @Test
    fun `sink blocks on slow reader in coroutines`() = runSuspendIO {
        val pipe = Pipe(3L)
        val job = launch {
            val buffer = Buffer()
            delay(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "abc"

            delay(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "def"

            delay(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "ghi"

            Thread.sleep(1000L)
            pipe.source.read(buffer, Long.MAX_VALUE) shouldBeEqualTo 3L
            buffer.readUtf8() shouldBeEqualTo "jkl"
        }

        val start = now()
        pipe.sink.write(bufferOf("abcdefghijkl"), 12L)
        assertElapsed(3000.0, start)
        job.join()
    }

    @Test
    fun `source 가 close되면 sink에 쓰기는 실패한다`() {
        val pipe = Pipe(3L)
        executor.schedule(
            {
                pipe.source.close()
            },
            1000L,
            TimeUnit.MILLISECONDS
        )
        val start = now()
        assertFailsWith<java.io.IOException> {
            pipe.sink.write(bufferOf("abcdef"), 6L) // pipe buffer size 보다 큰 값을 써야 예외가 발생한다
        }.apply {
            message shouldBeEqualTo "source is closed"
        }
        assertElapsed(1000.0, start)
    }


    @Test
    fun `모든 데이터를 읽기 전에 reader가 close 된다면 sink flush는 실패한다`() {
        val pipe = Pipe(100L)
        pipe.sink.write(bufferOf("abc"), 3L)
        pipe.source.close()

        assertFailsWith<java.io.IOException> {
            pipe.sink.flush()
        }
    }

    @Test
    fun `Reader가 모든 데이터를 읽기 전에 close 된다면 sink close 는 실패한다`() {
        val pipe = Pipe(100L)
        pipe.sink.write(bufferOf("abc"), 3L)
        pipe.source.close()

        assertFailsWith<java.io.IOException> {
            pipe.sink.close()
        }.message shouldBeEqualTo "source is closed"
    }

    @Test
    fun `sink close`() {
        val pipe = Pipe(100L)
        pipe.sink.close()

        assertFailsWith<IllegalStateException> {
            pipe.sink.write(bufferOf("abc"), 3L)
        }.message shouldBeEqualTo "closed"

        assertFailsWith<IllegalStateException> {
            pipe.sink.flush()
        }.message shouldBeEqualTo "closed"
    }

    @Test
    fun `sink multiple close`() {
        val pipe = Pipe(100L)
        pipe.sink.close()
        pipe.sink.close()
    }

    @Test
    fun `sink close 는 source read 를 기다리지 않는다`() {
        val pipe = Pipe(100L)
        pipe.sink.write(bufferOf("abc"), 3L)
        pipe.sink.close()

        val bufferedSource = pipe.source.buffered()
        bufferedSource.readUtf8(3L) shouldBeEqualTo "abc"
        bufferedSource.exhausted().shouldBeTrue()
    }

    @Test
    fun `source read 작업은 sink close 에 방해받지 않는다`() {
        val pipe = Pipe(3L)
        executor.schedule(
            {
                pipe.sink.close()
            },
            1000L,
            TimeUnit.MILLISECONDS
        )
        val start = now()
        val readBuffer = Buffer()
        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo -1L
        readBuffer.size shouldBeEqualTo 0L
        assertElapsed(1000.0, start)
    }

    /**
     * The writer has 12 bytes to write. It alternates sleeping 1000 ms, then writing 3 bytes. The
     * reader is reading as fast as it can. That should make for an approximate timeline like this:
     *
     * ```
     *    0: writer sleeps until 1000
     *    0: reader blocks
     * 1000: writer writes 'abc', sleeps until 2000
     * 1000: reader reads 'abc'
     * 2000: writer writes 'def', sleeps until 3000
     * 2000: reader reads 'def'
     * 3000: writer writes 'ghi', sleeps until 4000
     * 3000: reader reads 'ghi'
     * 4000: writer writes 'jkl', returns
     * 4000: reader reads 'jkl', returns
     * ```
     */
    @Test
    fun `source blocks on slow writer`() {
        val pipe = Pipe(100L)

        executor.execute {
            Thread.sleep(1000L)
            log.debug { "Write abc" }
            pipe.sink.write(bufferOf("abc"), 3L)

            Thread.sleep(1000L)
            log.debug { "Write def" }
            pipe.sink.write(bufferOf("def"), 3L)

            Thread.sleep(1000L)
            log.debug { "Write ghi" }
            pipe.sink.write(bufferOf("ghi"), 3L)

            Thread.sleep(1000L)
            log.debug { "Write jkl" }
            pipe.sink.write(bufferOf("jkl"), 3L)
        }

        val start = now()
        val readBuffer = Buffer()

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "abc"
        log.debug { "Read abc" }
        assertElapsed(1000.0, start)

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "def"
        log.debug { "Read def" }
        assertElapsed(2000.0, start)

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "ghi"
        log.debug { "Read ghi" }
        assertElapsed(3000.0, start)

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "jkl"
        log.debug { "Read jkl" }
        assertElapsed(4000.0, start)
    }


    /**
     * The writer has 12 bytes to write. It alternates sleeping 1000 ms, then writing 3 bytes. The
     * reader is reading as fast as it can. That should make for an approximate timeline like this:
     *
     * ```
     *    0: writer sleeps until 1000
     *    0: reader blocks
     * 1000: writer writes 'abc', sleeps until 2000
     * 1000: reader reads 'abc'
     * 2000: writer writes 'def', sleeps until 3000
     * 2000: reader reads 'def'
     * 3000: writer writes 'ghi', sleeps until 4000
     * 3000: reader reads 'ghi'
     * 4000: writer writes 'jkl', returns
     * 4000: reader reads 'jkl', returns
     * ```
     */
    @Test
    fun `source blocks on slow writer in coroutines`() = runSuspendIO {
        val pipe = Pipe(100L)

        val job = launch {
            delay(1000L)
            log.debug { "Write abc" }
            pipe.sink.write(bufferOf("abc"), 3L)

            delay(1000L)
            log.debug { "Write def" }
            pipe.sink.write(bufferOf("def"), 3L)

            delay(1000L)
            log.debug { "Write ghi" }
            pipe.sink.write(bufferOf("ghi"), 3L)

            delay(1000L)
            log.debug { "Write jkl" }
            pipe.sink.write(bufferOf("jkl"), 3L)
        }
        yield()

        val start = now()
        val readBuffer = Buffer()

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "abc"
        log.debug { "Read abc" }
        assertElapsed(1000.0, start)

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "def"
        log.debug { "Read def" }
        assertElapsed(2000.0, start)

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "ghi"
        log.debug { "Read ghi" }
        assertElapsed(3000.0, start)

        pipe.source.read(readBuffer, Long.MAX_VALUE) shouldBeEqualTo 3L
        readBuffer.readUtf8() shouldBeEqualTo "jkl"
        log.debug { "Read jkl" }
        assertElapsed(4000.0, start)

        job.join() // Wait for the job to complete
    }
}

package io.bluetape4k.workshop.coroutines.tests

import app.cash.turbine.test
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class TurbineExamples {

    companion object: KLoggingChannel()

    @Test
    fun `turbine을 이용하여 flow를 테스트`() = runTest {
        flowOf("one", "two")
            .log("#1")
            .test {
                awaitItem() shouldBeEqualTo "one"
                awaitItem() shouldBeEqualTo "two"
                awaitComplete()
            }
    }

    @Test
    fun `flow가 예외를 발생시키면 raise 된다`() = runTest {
        // Flow 안에서 예외 발생 시 검출하고, stacktrace 정보로 알 수 있다
        assertFailsWith<AssertionError> {
            flow<Int> {
                throw RuntimeException("Boom!")
            }
                .log("#1")
                .test {
                    // Nothing to do
                }
        }.cause shouldBeInstanceOf RuntimeException::class
    }

    @Test
    fun `비동기 flow 의 emit 작업을 블로킹한다`() = runTest {
        channelFlow {
            withContext(Dispatchers.IO) {
                Thread.sleep(10)
                send("item")
            }
        }
            .log("channel")
            .test {
                awaitItem() shouldBeEqualTo "item"
                awaitComplete()
            }
    }

    @Test
    fun `비동기 flow 내부에서 emit을 지연시키기`() = runTest {
        channelFlow {
            delay(10)
            send("item")
        }
            .log("channel")
            .test {
                awaitItem() shouldBeEqualTo "item"
                awaitComplete()
            }
    }

    @Test
    fun `turbine의 최대 대기시간은 1초이다`() = runTest(timeout = 500.milliseconds) {
        channelFlow {
            withContext(Dispatchers.IO) {
                Thread.sleep(10)
                send("item")
            }
        }
            .log("channel")
            .test {
                awaitItem() shouldBeEqualTo "item"
                awaitComplete()
            }
    }

    @Test
    fun `비동기 flow 처리를 취소할 수 있다`() = runTest {
        channelFlow {
            // Thread.sleep(10) 을 사용하면, 10개를 모두 send 해버립니다.
            // delay 를 사용하면, 중간에 중단할 수 있다
            withContext(Dispatchers.IO) {
                repeat(10) {
                    delay(10)
                    log.debug { "Sending item $it" }
                    send("item $it")
                }
            }
        }
            .log("channel")
            .test {
                awaitItem() shouldBeEqualTo "item 0"
                awaitItem() shouldBeEqualTo "item 1"
                awaitItem() shouldBeEqualTo "item 2"
                cancelAndIgnoreRemainingEvents()       // delay인 경우에는 emit 을 중단합니다. Thread.sleep인 경우에는 중단하지 못합니다.
                // cancel()
            }
    }

    @Test
    fun `delay되는 비동기 flow 처리를 취소할 수 있다`() = runTest {
        channelFlow {
            // 3개만 send하고, 더 이상 send 하지 않습니다.
            repeat(10) {
                delay(10)
                log.debug { "Sending item $it" }
                send("item $it")
            }
        }
            .log("channel")
            .test {
                awaitItem() shouldBeEqualTo "item 0"
                awaitItem() shouldBeEqualTo "item 1"
                awaitItem() shouldBeEqualTo "item 2"
                // cancelAndIgnoreRemainingEvents()  // delay인 경우에는 emit 을 중단합니다. Thread.sleep인 경우에는 중단하지 못합니다.
                cancel()                             // flow collect 를 중단한다
            }
    }
}

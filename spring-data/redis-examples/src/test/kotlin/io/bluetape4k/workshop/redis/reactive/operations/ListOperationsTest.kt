package io.bluetape4k.workshop.redis.reactive.operations

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.redis.reactive.AbstractReactiveRedisTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.ReactiveRedisOperations
import java.time.Duration
import java.util.logging.Level

class ListOperationsTest(
    @param:Autowired private val operations: ReactiveRedisOperations<String, String>,
): AbstractReactiveRedisTest() {

    companion object : KLoggingChannel() {
        private const val MESSAGE = "Hello World"
    }

    @BeforeEach
    fun beforeEach() = runSuspendIO {
        operations.execute { conn ->
            conn.serverCommands().flushDb()
        }.awaitSingle() shouldBeEqualTo "OK"
    }

    /**
     * Redis blocking list 명령인 `BRPOP` 과 `LPUSH` 로 큐 메시지를 생산하는 단순 큐입니다.
     */
    @Test
    fun `poll and populate queue`() = runSuspendIO {
        val queue = "simple-queue"
        val listOps = operations.opsForList()

        // BRPOP 로 메시지를 소비합니다.
        val brpop = listOps.rightPop(queue, Duration.ofSeconds(5))
            .log("workshop.redis.examples.reactive", Level.INFO)
        log.debug { "BRPOP ... wating for message" }
        delay(5)

        // LPUSH 로 메시지를 생산합니다.
        listOps.leftPush(queue, MESSAGE).awaitSingle() shouldBeEqualTo 1
        delay(5)

        val message = brpop.awaitSingleOrNull()
        log.debug { "BRPOP ... done! message=$message" }
        message shouldBeEqualTo MESSAGE
    }
}

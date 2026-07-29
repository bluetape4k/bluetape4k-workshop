package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.*
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T6: Redis failure 교육용 smoke test입니다.
 *
 * Redis가 operation 중 사용할 수 없게 될 때 library 동작을 보여줍니다.
 * 테스트 전에 중지하는 isolated Testcontainers Redis container를 사용합니다.
 *
 * 중요: 공유 singleton인 `RedisServer.Launcher.redis`를 사용하지 않습니다.
 * singleton을 중지하면 다른 모든 테스트가 깨집니다.
 *
 * `junit.jupiter.execution.exclude.tags=smoke`로 기본 `test` task에서 제외됩니다.
 */
@Tag("smoke")
class RedisFailureTest {

    @Test
    fun `runIfLeader throws exception when Redis is unavailable`() = runSuspendIO(timeout = 10.seconds) {
        // 공유 singleton이 아니라 dedicated container를 사용합니다.
        val redisContainer = RedisServer(image = RedisServer.IMAGE, tag = "7-alpine").apply { start() }
        val client = RedisClient.create(redisContainer.url)

        try {
            val connection = client.connect(StringCodec.UTF8)
            val options = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)
            val elector = LettuceLeaderElector(connection, options)
            val lockName = "test:t6:${Base58.randomString(8)}"

            try {
                // lock 획득을 시도하기 전에 Redis를 중지합니다.
                redisContainer.stop()

                // runSuspendIO timeout은 무한 hang을 막고, assertFailsWith는 Redis failure 전파를 검증합니다.
                assertFailsWith<Exception> {
                    elector.runIfLeader(lockName) { "should-not-execute" }
                }

                log.info { "[T6] Redis failure correctly propagated as exception — no silent hang" }
            } finally {
                connection.closeSafe()
            }
        } finally {
            runCatching { client.shutdown() }
            runCatching { redisContainer.stop() }
        }
    }

    companion object : KLogging()
}

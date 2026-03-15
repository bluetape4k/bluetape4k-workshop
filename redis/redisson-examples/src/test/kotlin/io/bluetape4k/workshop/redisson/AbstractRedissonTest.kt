package io.bluetape4k.workshop.redisson

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.redis.redisson.codec.RedissonCodecs
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeAll
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import java.time.Duration

abstract class AbstractRedissonTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }

        @JvmStatic
        val redissonClient: Redisson by lazy { createRedisson() }

        private fun createRedisson(): Redisson {
            val config = Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(256)
                    .setConnectionMinimumIdleSize(32) // 최소 연결을 충분히 확보하여 Latency 방지
                    .setIdleConnectionTimeout(100_000)  // 연결 유지를 넉넉히 (100초)
                    .setTimeout(1000)
                    .setRetryAttempts(3)
                    .setRetryDelay { attempt -> Duration.ofMillis((attempt + 1) * 10L) }

                    .setDnsMonitoringInterval(5000)  // DNS 변경 감지 (Cloud 환경 필수)

                executor = VirtualThreadExecutor
                threads = 256
                nettyThreads = 256
                codec = RedissonCodecs.LZ4Fory
                setTcpNoDelay(true)
            }

            return Redisson.create(config).apply {
                ShutdownQueue.register { shutdown() }
            } as Redisson
        }

        @JvmStatic
        val faker = Fakers.faker

        @JvmStatic
        fun randomString(): String =
            Fakers.randomString(1024, 2048)

        @JvmStatic
        fun randomName(prefix: String = "kotlin"): String =
            "$prefix:${Base58.randomString(6)}:${Base58.randomString(6)}"
    }

    protected val redisson: Redisson get() = redissonClient

    // Lettuce Client 는 Redis의 RAW 명령어를 실행하기 위해서 사용합니다.
    protected val commands by lazy {
        RedisServer.Launcher.LettuceLib.getRedisCommands(redis.host, redis.port)
    }

    protected fun newRedisson(): RedissonClient {
        return createRedisson()
    }

    protected val scope = CoroutineScope(CoroutineName("redisson") + Dispatchers.IO)

    protected val exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { context, error ->
            log.error(error) {
                "CoroutineExceptionHandler get error with suppressed ${error.suppressed.contentToString()} "
            }
            throw RuntimeException("Fail to execute in coroutine", error)
        }

    @BeforeAll
    fun beforeAll() {
        // 참고: [Redis Keyspace notifications](https://redis.io/docs/latest/develop/use/keyspace-notifications/)
        // redis RAW 명령인 `config set` 을 실행하기 위해 Lettuce Client를 사용한다
        commands.configSet("notify-keyspace-events", "AKE")
    }
}

package io.bluetape4k.workshop.redisson

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.error
import io.bluetape4k.testcontainers.storage.RedisServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeAll
import org.redisson.Redisson
import org.redisson.api.RedissonClient

abstract class AbstractRedissonTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }

        @JvmStatic
        val redissonClient by lazy {
            RedisServer.Launcher.RedissonLib.getRedisson(
                redis.url,
                connectionPoolSize = 256,
                minimumIdleSize = 16,
                threads = 128,
                nettyThreads = 512
            ) as Redisson
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
        val config = RedisServer.Launcher.RedissonLib.getRedissonConfig(redis.url)
        return Redisson.create(config)
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

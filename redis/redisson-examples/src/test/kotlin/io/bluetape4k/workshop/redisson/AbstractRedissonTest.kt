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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeAll
import org.redisson.Redisson
import org.redisson.api.RFuture
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class AbstractRedissonTest {

    companion object : KLoggingChannel() {
        @JvmStatic
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }

        @JvmStatic
        val redissonClient: Redisson by lazy { createRedisson(registerShutdownHook = true) }

        private fun createRedissonConfig(): Config {
            return Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(128)
                    .setConnectionMinimumIdleSize(32) // 지연 급증을 피하려고 충분한 idle connection을 유지합니다.
                    .setIdleConnectionTimeout(100_000)  // idle connection을 100초 동안 유지합니다.
                    .setTimeout(5000)
                    .setRetryAttempts(3)
                    .setRetryDelay { attempt -> Duration.ofMillis((attempt + 1) * 10L) }

                    .setDnsMonitoringInterval(5000)  // cloud 환경의 DNS 변경을 감지합니다.

                executor = VirtualThreadExecutor
                threads = 256
                nettyThreads = 128
                codec = RedissonCodecs.LZ4FastForyComposite
                setTcpNoDelay(true)
                setTcpUserTimeout(5000)
            }
        }

        private fun createRedisson(registerShutdownHook: Boolean = false): Redisson {
            return (Redisson.create(createRedissonConfig()) as Redisson).apply {
                if (registerShutdownHook) {
                    ShutdownQueue.register { shutdown() }
                }
            }
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

    // raw Redis command 실행에는 Lettuce를 사용합니다.
    protected val commands by lazy {
        RedisServer.Launcher.LettuceLib.getRedisCommands(redis.host, redis.port)
    }

    protected fun newRedisson(registerShutdown: Boolean = true): RedissonClient {
        return createRedisson(registerShutdown)
    }

    protected fun newRedissonConfig(): Config {
        return createRedissonConfig()
    }

    protected val scope = CoroutineScope(CoroutineName("redisson") + Dispatchers.IO)

    protected val exceptionHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { context, error ->
            log.error(error) {
                "CoroutineExceptionHandler get error with suppressed ${error.suppressed.contentToString()} "
            }
            throw RuntimeException("Fail to execute in coroutine", error)
        }

    /**
     * Redisson 비동기 호출을 bounded coroutine suspension으로 소비합니다.
     *
     * 호출자 취소 또는 timeout이 발생하면 아직 완료되지 않은 Redis future에도
     * 취소를 전파하여 테스트 종료 뒤 pending operation을 남기지 않습니다.
     */
    protected suspend fun <T> awaitRedis(
        future: RFuture<T>,
        timeout: kotlin.time.Duration = 5.seconds,
    ): T = try {
        withTimeout(timeout) { future.await() }
    } catch (cause: TimeoutCancellationException) {
        future.cancel(false)
        throw cause
    } catch (cause: CancellationException) {
        future.cancel(false)
        throw cause
    }

    @BeforeAll
    fun beforeAll() {
        // 참고: [Redis Keyspace notifications](https://redis.io/docs/latest/develop/use/keyspace-notifications/)
        // raw Redis `config set` command 실행에는 Lettuce를 사용합니다.
        commands.configSet("notify-keyspace-events", "AKE")
    }
}

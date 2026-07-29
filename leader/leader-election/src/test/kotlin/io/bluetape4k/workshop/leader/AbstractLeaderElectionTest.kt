package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 모든 leader election test의 base class입니다.
 *
 * 공유 Testcontainers Redis instance(bluetape4k Launcher singleton pattern 사용)와
 * isolated connection을 가진 [LettuceLeaderElector] instance를 만드는 factory helper를 제공합니다.
 *
 * ## 동작 / 계약
 * - `RedisServer.Launcher.redis` singleton을 사용하므로 `@Testcontainers` annotation이 필요 없습니다.
 * - concurrent elector instance 사이의 shared state를 피하려면 각 테스트는 [newConnection]으로
 *   자체 [StatefulRedisConnection]을 만들어야 합니다.
 * - 빠르고 결정적인 테스트를 위해 기본 option은 `waitTime = 100ms`, `leaseTime = 5s`입니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderElectionTest {

    companion object : KLogging() {
        /** 전체 test suite에서 한 번 시작하는 공유 Redis Testcontainer입니다. */
        val redis = RedisServer.Launcher.redis

        /** 실행 중인 container에서 얻은 Redis URL입니다. */
        val redisUrl: String get() = redis.url

        /** 공유 Redis client입니다. 개별 테스트는 여전히 isolated connection을 받습니다. */
        val client: RedisClient by lazy {
            RedisClient.create(redisUrl).also {
                ShutdownQueue.register { runCatching { it.shutdown() } }
            }
        }

        /** 결정적인 테스트를 위한 기본 option입니다. 빠른 wait와 짧은 lease를 사용합니다. */
        val defaultOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )
    }

    /** 새 [StatefulRedisConnection]을 열고 test-suite shutdown 대상에 등록합니다. */
    protected fun newConnection(): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8).also {
            ShutdownQueue.register { it.closeSafe() }
        }

    /** 자체 connection과 지정한 option을 가진 새 [LettuceLeaderElector]를 만듭니다. */
    protected fun newElector(
        options: LeaderElectionOptions = defaultOptions,
    ): LettuceLeaderElector = LettuceLeaderElector(newConnection(), options)

    /** 자체 connection과 지정한 option을 가진 새 [LettuceSuspendLeaderElector]를 만듭니다. */
    protected fun newSuspendElector(
        options: LeaderElectionOptions = defaultOptions,
    ): LettuceSuspendLeaderElector = LettuceSuspendLeaderElector(newConnection(), options)

    /** [LettuceLeaderElector]를 감싸는 새 [ListeningLeaderElector]를 만듭니다. */
    protected fun newListeningElector(
        options: LeaderElectionOptions = defaultOptions,
    ): ListeningLeaderElector = ListeningLeaderElector(newElector(options))
}

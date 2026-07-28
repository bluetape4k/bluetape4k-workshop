package io.bluetape4k.workshop.leader.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.ListeningLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.toKotlinDuration

/**
 * leader election infrastructure를 위한 Spring Boot configuration입니다.
 *
 * [RedisClient], [StatefulRedisConnection], [ListeningLeaderElector],
 * [LettuceSuspendLeaderElector]를 Spring bean으로 연결합니다.
 *
 * ## 동작 / 계약
 * - context가 닫힐 때 [redisClient]는 `destroyMethod = "shutdown"`으로 종료합니다.
 * - [lettuceConnection]과 [lettuceSuspendConnection]은 `destroyMethod = "close"`로 닫습니다.
 * - primary [leaderElector] bean은 [LettuceLeaderElector]를 감싼 [ListeningLeaderElector]입니다.
 *   모든 `runIfLeader` 호출은 [ListeningLeaderElector.events] 또는
 *   [io.bluetape4k.leader.LeaderElectionListener]로 관찰 가능한
 *   [io.bluetape4k.leader.LeaderElectionEvent]를 내보냅니다.
 * - [suspendLeaderElector]는 blocking elector와 pipelined command state를 공유하지 않도록
 *   두 번째 dedicated connection을 엽니다.
 * - [LeaderElectionOptions]는 `kotlin.time.Duration`을 요구하고, [LeaderElectionProperties]는
 *   Spring `@ConfigurationProperties` binding용 `java.time.Duration`을 보관합니다.
 *   여기서 `toKotlinDuration()` 변환을 명시적으로 적용합니다.
 */
@Configuration
@EnableConfigurationProperties(LeaderElectionProperties::class)
class LeaderElectionConfig {

    companion object : KLogging()

    /**
     * [LeaderElectionProperties]의 URL을 사용해 [RedisClient]를 만듭니다.
     * Spring context가 닫힐 때 `destroyMethod = "shutdown"`으로 종료합니다.
     */
    @Bean(destroyMethod = "shutdown")
    fun redisClient(props: LeaderElectionProperties): RedisClient {
        log.debug { "Creating RedisClient with url=${props.redis.url}" }
        return RedisClient.create(props.redis.url)
    }

    /**
     * blocking [LettuceLeaderElector]용 primary [StatefulRedisConnection]을 엽니다.
     * Spring context가 닫힐 때 `destroyMethod = "close"`로 닫습니다.
     */
    @Bean(destroyMethod = "close")
    fun lettuceConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8)

    /**
     * [LettuceSuspendLeaderElector]용 dedicated [StatefulRedisConnection]을 엽니다.
     *
     * 별도 connection은 blocking elector와 suspend elector가 pipelined command state를
     * 공유하지 않게 해 concurrent use에서 발생할 수 있는 ordering issue를 막습니다.
     */
    @Bean(name = ["lettuceSuspendConnection"], destroyMethod = "close")
    fun lettuceSuspendConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8)

    /**
     * [LettuceLeaderElector]를 감싸는 [ListeningLeaderElector]를 만듭니다.
     *
     * [ListeningLeaderElector]를 primary [io.bluetape4k.leader.LeaderElector] bean으로 사용하면
     * [io.bluetape4k.workshop.leader.job.LeaderScheduledJobService] 안의 호출을 포함한
     * 모든 `runIfLeader` 호출이 [io.bluetape4k.workshop.leader.service.LeaderEventListenerService]에서
     * 관찰 가능한 [io.bluetape4k.leader.LeaderElectionEvent]를 내보냅니다.
     *
     * 중요: [LeaderElectionOptions]는 `kotlin.time.Duration`을 요구하고,
     * [LeaderElectionProperties]는 Spring binding용 `java.time.Duration`을 저장합니다.
     * 명시적인 `toKotlinDuration()` 변환이 필수입니다.
     */
    @Bean
    fun leaderElector(
        @Qualifier("lettuceConnection") connection: StatefulRedisConnection<String, String>,
        props: LeaderElectionProperties,
    ): ListeningLeaderElector = ListeningLeaderElector(
        LettuceLeaderElector(
            connection,
            LeaderElectionOptions(
                waitTime = props.waitTime.toKotlinDuration(),
                leaseTime = props.leaseTime.toKotlinDuration(),
            ),
        )
    )

    /**
     * coroutine 기반 leader election을 위한 [LettuceSuspendLeaderElector]를 만듭니다.
     *
     * blocking [leaderElector]와 pipelined command state를 공유하지 않도록
     * dedicated [lettuceSuspendConnection]을 사용합니다.
     */
    @Bean
    fun suspendLeaderElector(
        @Qualifier("lettuceSuspendConnection") lettuceSuspendConnection: StatefulRedisConnection<String, String>,
        props: LeaderElectionProperties,
    ): LettuceSuspendLeaderElector = LettuceSuspendLeaderElector(
        connection = lettuceSuspendConnection,
        options = LeaderElectionOptions(
            waitTime = props.waitTime.toKotlinDuration(),
            leaseTime = props.leaseTime.toKotlinDuration(),
        ),
    )
}

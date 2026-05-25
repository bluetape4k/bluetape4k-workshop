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
 * Spring Boot configuration for the leader election infrastructure.
 *
 * Wires [RedisClient], [StatefulRedisConnection], [ListeningLeaderElector], and
 * [LettuceSuspendLeaderElector] as Spring beans.
 *
 * ## Behavior / Contract
 * - [redisClient] is shut down via `destroyMethod = "shutdown"` on context close.
 * - [lettuceConnection] and [lettuceSuspendConnection] are closed via `destroyMethod = "close"`.
 * - The primary [leaderElector] bean is a [ListeningLeaderElector] wrapping a
 *   [LettuceLeaderElector]. All `runIfLeader` calls emit [io.bluetape4k.leader.LeaderElectionEvent]s
 *   observable via [ListeningLeaderElector.events] or [io.bluetape4k.leader.LeaderElectionListener].
 * - [suspendLeaderElector] opens a second dedicated connection to avoid shared pipelined
 *   command state with the blocking elector.
 * - [LeaderElectionOptions] requires `kotlin.time.Duration`; [LeaderElectionProperties] carries
 *   `java.time.Duration` (Spring `@ConfigurationProperties` binding). `toKotlinDuration()`
 *   conversion is applied explicitly here.
 */
@Configuration
@EnableConfigurationProperties(LeaderElectionProperties::class)
class LeaderElectionConfig {

    companion object : KLogging()

    /**
     * Creates a [RedisClient] using the URL from [LeaderElectionProperties].
     * Shut down via `destroyMethod = "shutdown"` on Spring context close.
     */
    @Bean(destroyMethod = "shutdown")
    fun redisClient(props: LeaderElectionProperties): RedisClient {
        log.debug { "Creating RedisClient with url=${props.redis.url}" }
        return RedisClient.create(props.redis.url)
    }

    /**
     * Opens the primary [StatefulRedisConnection] for the blocking [LettuceLeaderElector].
     * Closed via `destroyMethod = "close"` on Spring context close.
     */
    @Bean(destroyMethod = "close")
    fun lettuceConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8)

    /**
     * Opens a dedicated [StatefulRedisConnection] for [LettuceSuspendLeaderElector].
     *
     * A separate connection prevents the blocking and suspend electors from sharing
     * pipelined command state, which can cause ordering issues under concurrent use.
     */
    @Bean(name = ["lettuceSuspendConnection"], destroyMethod = "close")
    fun lettuceSuspendConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8)

    /**
     * Creates a [ListeningLeaderElector] that wraps a [LettuceLeaderElector].
     *
     * Using [ListeningLeaderElector] as the primary [io.bluetape4k.leader.LeaderElector] bean
     * ensures all `runIfLeader` calls — including those in
     * [io.bluetape4k.workshop.leader.job.LeaderScheduledJobService] — emit
     * [io.bluetape4k.leader.LeaderElectionEvent]s observable by
     * [io.bluetape4k.workshop.leader.service.LeaderEventListenerService].
     *
     * IMPORTANT: [LeaderElectionOptions] requires `kotlin.time.Duration`, while
     * [LeaderElectionProperties] stores `java.time.Duration` (Spring binding).
     * Explicit `toKotlinDuration()` conversion is mandatory.
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
     * Creates a [LettuceSuspendLeaderElector] for coroutine-based leader election.
     *
     * Uses a dedicated [lettuceSuspendConnection] to avoid sharing pipelined command state
     * with the blocking [leaderElector].
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

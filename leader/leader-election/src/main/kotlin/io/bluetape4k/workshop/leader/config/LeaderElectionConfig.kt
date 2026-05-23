package io.bluetape4k.workshop.leader.config

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.*
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.toKotlinDuration

/**
 * Spring Boot configuration for the leader election infrastructure.
 *
 * Wires [RedisClient], [StatefulRedisConnection], and [LeaderElector] as Spring beans.
 *
 * ## Behavior / Contract
 * - [redisClient] is shut down via `destroyMethod = "shutdown"` on context close.
 * - [lettuceConnection] is closed via `destroyMethod = "close"` on context close.
 * - [LeaderElectionOptions] uses `kotlin.time.Duration`; [LeaderElectionProperties] carries
 *   `java.time.Duration` (Spring `@ConfigurationProperties` binding). The conversion
 *   `toKotlinDuration()` is applied explicitly here.
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
     * Opens a [StatefulRedisConnection] from the given [RedisClient].
     * Closed via `destroyMethod = "close"` on Spring context close.
     */
    @Bean(destroyMethod = "close")
    fun lettuceConnection(client: RedisClient): StatefulRedisConnection<String, String> {
        return client.connect(StringCodec.UTF8)
    }

    /**
     * Creates a [LeaderElector] backed by Lettuce/Redis.
     *
     * IMPORTANT: [LeaderElectionOptions] requires `kotlin.time.Duration`, while
     * [LeaderElectionProperties] stores `java.time.Duration` (Spring binding).
     * Explicit `toKotlinDuration()` conversion is mandatory here.
     */
    @Bean
    fun leaderElector(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderElectionProperties,
    ): LeaderElector = LettuceLeaderElector(
        connection,
        LeaderElectionOptions(
            waitTime = props.waitTime.toKotlinDuration(),
            leaseTime = props.leaseTime.toKotlinDuration(),
        ),
    )
}

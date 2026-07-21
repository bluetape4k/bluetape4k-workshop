package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLeasePort
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderElectionPort
import io.bluetape4k.workshop.leader.jobsafety.coordination.redis.RedisJobFencingLeaseAdapter
import io.bluetape4k.workshop.leader.jobsafety.coordination.redis.RedisLeaderElectionAdapter
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.toKotlinDuration

@ConfigurationProperties("workshop.job-safety")
data class JobSafetyProperties(
    val region: String = "region-a",
    val namespaceEpoch: Long = 1L,
    val timelineLimit: Int = 128,
    val defaultTimeout: Duration = Duration.ofSeconds(1),
    val fencing: Fencing = Fencing(),
    val redis: Redis = Redis(),
) {
    data class Fencing(
        val leaseTtl: Duration = Duration.ofSeconds(5),
        val renewInterval: Duration = Duration.ofSeconds(2),
    )

    data class Redis(
        val uri: String = "redis://localhost:6379",
        val timeout: Duration = Duration.ofSeconds(1),
    )
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobSafetyProperties::class)
class JobSafetyConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun jobSafetyRedisClient(properties: JobSafetyProperties): RedisClient =
        RedisClient.create(properties.redis.uri)

    @Bean(destroyMethod = "close")
    fun jobSafetyRedisConnection(
        @Qualifier("jobSafetyRedisClient") client: RedisClient,
    ): StatefulRedisConnection<String, String> = client.connect(StringCodec.UTF8)

    @Bean
    fun jobSafetyLeaderElector(
        @Qualifier("jobSafetyRedisConnection") connection: StatefulRedisConnection<String, String>,
        properties: JobSafetyProperties,
    ): LeaderElector =
        LettuceLeaderElector(
            connection = connection,
            options =
                LeaderElectionOptions(
                    waitTime = properties.defaultTimeout.toKotlinDuration(),
                    leaseTime = properties.fencing.leaseTtl.toKotlinDuration(),
                    autoExtend = true,
                ),
        )

    @Bean(destroyMethod = "close")
    fun jobSafetyLeaderExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun leaderElectionPort(
        @Qualifier("jobSafetyLeaderElector") backend: LeaderElector,
        @Qualifier("jobSafetyLeaderExecutor") executor: ExecutorService,
    ): LeaderElectionPort = RedisLeaderElectionAdapter(backend, executor)

    @Bean
    fun fencingLeasePort(
        @Qualifier("jobSafetyRedisConnection") connection: StatefulRedisConnection<String, String>,
        properties: JobSafetyProperties,
    ): FencingLeasePort =
        RedisJobFencingLeaseAdapter(
            commands = connection.sync(),
            namespaceEpoch = NamespaceEpoch(properties.namespaceEpoch),
        )
}

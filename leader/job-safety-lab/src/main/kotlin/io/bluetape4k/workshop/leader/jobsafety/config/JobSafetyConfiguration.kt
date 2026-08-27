package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.micrometer.LeaderMetricTagMode
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.micrometer.LeaderMetricTagRule
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderAopMetricsRecorder
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderElectionListener
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLeasePort
import io.bluetape4k.workshop.leader.jobsafety.coordination.JobRunCoordinator
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderElectionPort
import io.bluetape4k.workshop.leader.jobsafety.coordination.redis.RedisJobFencingLeaseAdapter
import io.bluetape4k.workshop.leader.jobsafety.coordination.redis.RedisLeaderElectionAdapter
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.bluetape4k.workshop.leader.jobsafety.effect.DeterministicExternalEffectAdapter
import io.bluetape4k.workshop.leader.jobsafety.effect.EffectOperations
import io.bluetape4k.workshop.leader.jobsafety.effect.ExternalEffectPort
import io.bluetape4k.workshop.leader.jobsafety.effect.OutboxEffectWorker
import io.bluetape4k.workshop.leader.jobsafety.execution.FencedJobExecutionService
import io.bluetape4k.workshop.leader.jobsafety.persistence.JOB_SAFETY_TABLES
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyJdbcExecutor
import io.bluetape4k.workshop.leader.jobsafety.persistence.JobSafetyRepositories
import io.bluetape4k.workshop.leader.jobsafety.scenario.JobSafetyScenarioService
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import io.micrometer.observation.ObservationRegistry
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import javax.sql.DataSource
import kotlin.time.toKotlinDuration
import io.bluetape4k.concurrent.virtualthread.VirtualThreads

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobSafetyProperties::class)
class JobSafetyConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObservationRegistry::class)
    fun jobSafetyLeaderObservationRegistry(): ObservationRegistry = ObservationRegistry.NOOP

    /** Redis owner ID와 job lock을 12자리 hash로 제한하는 수동 observation wiring입니다. */
    @Bean
    @ConditionalOnMissingBean(LeaderObservationOptions::class)
    fun jobSafetyLeaderObservationOptions(): LeaderObservationOptions = defaultLeaderObservationOptions()

    @Bean
    @ConditionalOnMissingBean(MicrometerObservationLeaderAopMetricsRecorder::class)
    fun jobSafetyLeaderObservationRecorder(
        registry: ObservationRegistry,
        options: LeaderObservationOptions,
    ): MicrometerObservationLeaderAopMetricsRecorder =
        MicrometerObservationLeaderAopMetricsRecorder(registry, options)

    @Bean
    @ConditionalOnMissingBean(MicrometerObservationLeaderElectionListener::class)
    fun jobSafetyLeaderObservationListener(
        registry: ObservationRegistry,
        options: LeaderObservationOptions,
    ): MicrometerObservationLeaderElectionListener =
        MicrometerObservationLeaderElectionListener(registry, options)

    @Bean(destroyMethod = "shutdown")
    fun jobSafetyRedisClient(properties: JobSafetyProperties): RedisClient =
        RedisClient.create(properties.redis.uri)

    @Bean(destroyMethod = "close")
    fun jobSafetyRedisConnection(
        @Qualifier("jobSafetyRedisClient") client: RedisClient,
        properties: JobSafetyProperties,
    ): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8).also { it.setTimeout(properties.redis.commandTimeout) }

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
    fun jobSafetyLeaderExecutor(): ExecutorService = VirtualThreads.executorService()

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
            connection = connection,
            namespaceEpoch = NamespaceEpoch(properties.namespaceEpoch),
        )

    @Bean
    fun jobRunCoordinator(
        leaderElection: LeaderElectionPort,
        fencingLease: FencingLeasePort,
        properties: JobSafetyProperties,
        observationRecorder: MicrometerObservationLeaderAopMetricsRecorder,
        observationListener: MicrometerObservationLeaderElectionListener,
    ): JobRunCoordinator =
        JobRunCoordinator(
            leaderElection = leaderElection,
            fencingLease = fencingLease,
            fencingTtl = properties.fencing.leaseTtl,
            observationRecorder = observationRecorder,
            observationListener = observationListener,
        )

    @Bean
    fun jobSafetyJdbcExecutor(dataSource: DataSource): JobSafetyJdbcExecutor = JobSafetyJdbcExecutor(dataSource)

    @Bean
    fun jobSafetyRepositories(jdbc: JobSafetyJdbcExecutor): JobSafetyRepositories = JobSafetyRepositories(jdbc)

    @Bean
    fun fencedJobExecutionService(
        jdbc: JobSafetyJdbcExecutor,
        repositories: JobSafetyRepositories,
    ): FencedJobExecutionService = FencedJobExecutionService(jdbc, repositories)

    @Bean
    fun jobSafetyScenarioService(properties: JobSafetyProperties): JobSafetyScenarioService =
        JobSafetyScenarioService(properties.timelineLimit)

    @Bean
    fun externalEffectPort(): ExternalEffectPort = DeterministicExternalEffectAdapter()

    @Bean
    fun effectOperations(
        repositories: JobSafetyRepositories,
        provider: ExternalEffectPort,
        properties: JobSafetyProperties,
    ): EffectOperations =
        OutboxEffectWorker(
            outbox = repositories.outbox,
            receipts = repositories.effectReceipt,
            provider = provider,
            claimTimeout = properties.outbox.claimTimeout,
        )

    @Bean
    fun jobSafetySchemaInitializer(jdbc: JobSafetyJdbcExecutor): ApplicationRunner =
        ApplicationRunner {
            jdbc.transaction {
                withExposed { SchemaUtils.createMissingTablesAndColumns(*JOB_SAFETY_TABLES) }
            }
        }
}

private fun defaultLeaderObservationOptions(): LeaderObservationOptions =
    LeaderObservationOptions(
        includeLockName = true,
        includeLeaderId = true,
        tagOptions = LeaderMetricTagOptions(
            lockName = LeaderMetricTagRule(mode = LeaderMetricTagMode.HASH, hashLength = 12),
            leaderId = LeaderMetricTagRule(mode = LeaderMetricTagMode.HASH, hashLength = 12),
            defaultRule = LeaderMetricTagRule(mode = LeaderMetricTagMode.HASH, hashLength = 12),
        ),
    )

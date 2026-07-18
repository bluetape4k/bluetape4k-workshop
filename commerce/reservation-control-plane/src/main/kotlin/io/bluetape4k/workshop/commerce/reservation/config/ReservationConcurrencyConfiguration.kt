package io.bluetape4k.workshop.commerce.reservation.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandExecutionGate
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import io.bluetape4k.workshop.commerce.reservation.redis.AdmissionPermitBackend
import io.bluetape4k.workshop.commerce.reservation.redis.CommandSuppressionBackend
import io.bluetape4k.workshop.commerce.reservation.redis.InFlightCommandSuppressor
import io.bluetape4k.workshop.commerce.reservation.redis.LettuceLockSuppressionBackend
import io.bluetape4k.workshop.commerce.reservation.redis.LettuceSemaphoreAdmissionBackend
import io.bluetape4k.workshop.commerce.reservation.redis.NodeLocalDatabaseBulkhead
import io.bluetape4k.workshop.commerce.reservation.redis.ReservationAdmissionGate
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Qualifier
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.bluetape4k.workshop.commerce.reservation.sweeper.LeaderElectorSweepGate
import io.bluetape4k.workshop.commerce.reservation.sweeper.ReservationExpirySweeper
import io.bluetape4k.workshop.commerce.reservation.sweeper.ReservationSweepWork

@Configuration(proxyBeanMethods = false)
internal class ReservationConcurrencyConfiguration {
    @Bean
    fun nodeLocalDatabaseBulkhead(): NodeLocalDatabaseBulkhead = NodeLocalDatabaseBulkhead(
        foregroundPermits = 5,
        backgroundPermits = 1,
        acquireTimeout = Duration.ofMillis(100),
    )

    @Bean
    fun reservationAdmissionGate(
        localBulkhead: NodeLocalDatabaseBulkhead,
        redisBackend: ObjectProvider<AdmissionPermitBackend>,
    ): ReservationAdmissionGate = ReservationAdmissionGate(localBulkhead, redisBackend.ifAvailable)

    @Bean
    fun inFlightCommandSuppressor(
        redisBackend: ObjectProvider<CommandSuppressionBackend>,
    ): InFlightCommandSuppressor = InFlightCommandSuppressor(redisBackend.ifAvailable)

    @Bean
    fun reservationCommandExecutionGate(
        admission: ReservationAdmissionGate,
        suppression: InFlightCommandSuppressor,
        credentials: ReservationCredentialService,
        @Value("\${reservation.tenant-id}") tenantId: String,
    ): ReservationCommandExecutionGate =
        ReservationCommandExecutionGate(admission, suppression, credentials, tenantId)

    @Bean
    fun reservationExpirySweeper(
        @Qualifier("reservationLeaderElector")
        leaderElector: ObjectProvider<LeaderElector>,
        sweepWork: ReservationSweepWork,
        @Value("\${reservation.instance-id:#{T(java.util.UUID).randomUUID().toString()}}") instanceId: String,
    ): ReservationExpirySweeper? = leaderElector.ifAvailable?.let {
        ReservationExpirySweeper(
            leaderGate = LeaderElectorSweepGate(it),
            sweepWork = sweepWork,
            instanceId = instanceId,
        )
    }

    companion object : KLogging()
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "reservation.redis", name = ["enabled"], havingValue = "true")
internal class ReservationRedisConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun reservationRedisClient(@Value("\${reservation.redis.uri}") uri: String): RedisClient =
        LettuceClients.clientOf(uri).also { log.info { "reservation_redis_client_created" } }

    @Bean(destroyMethod = "close")
    fun reservationRedisConnection(client: RedisClient): StatefulRedisConnection<String, String>? =
        try {
            LettuceClients.connect(client)
        } catch (_: Exception) {
            log.warn { "reservation_redis_connection_failed fallback=POSTGRES" }
            null
        }

    @Bean
    fun reservationAdmissionBackend(
        connection: ObjectProvider<StatefulRedisConnection<String, String>>,
    ): AdmissionPermitBackend? = connection.ifAvailable?.let { LettuceSemaphoreAdmissionBackend(it) }

    @Bean
    fun reservationSuppressionBackend(
        connection: ObjectProvider<StatefulRedisConnection<String, String>>,
    ): CommandSuppressionBackend? = connection.ifAvailable?.let { LettuceLockSuppressionBackend(it) }

    @Bean
    fun reservationLeaderElector(
        connection: ObjectProvider<StatefulRedisConnection<String, String>>,
    ): LeaderElector? = connection.ifAvailable?.let {
        LettuceLeaderElector(
            it,
            LeaderElectionOptions(
                waitTime = kotlin.time.Duration.ZERO,
                leaseTime = 15.seconds,
                autoExtend = true,
            ),
        )
    }

    companion object : KLogging()
}

package io.bluetape4k.workshop.commerce.reservation.config

import io.bluetape4k.javatimes.millis
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
import io.bluetape4k.workshop.commerce.reservation.sweeper.LeaderElectorSweepGate
import io.bluetape4k.workshop.commerce.reservation.sweeper.ReservationExpirySweeper
import io.bluetape4k.workshop.commerce.reservation.sweeper.ReservationSweepWork
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.time.Duration.Companion.seconds

/**
 * 항상 켜져 있는 local JDBC protection과 optional distributed coordination을 연결합니다.
 *
 * Redis bean이 없으면 startup을 막는 대신 의도적으로 PostgreSQL-backed execution으로 degrade합니다.
 */
@Configuration(proxyBeanMethods = false)
internal class ReservationConcurrencyConfiguration {

    companion object: KLogging()

    /**
     * nodeLocalDatabaseBulkhead은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun nodeLocalDatabaseBulkhead(): NodeLocalDatabaseBulkhead =
        NodeLocalDatabaseBulkhead(
            foregroundPermits = 5,
            backgroundPermits = 1,
            acquireTimeout = 100.millis()
        )

    /**
     * reservationAdmissionGate은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param localBulkhead 업무 처리에 필요한 localBulkhead 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param redisBackend 업무 처리에 필요한 redisBackend 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun reservationAdmissionGate(
        localBulkhead: NodeLocalDatabaseBulkhead,
        redisBackend: ObjectProvider<AdmissionPermitBackend>,
    ): ReservationAdmissionGate =
        ReservationAdmissionGate(localBulkhead, redisBackend.ifAvailable)

    /**
     * inFlightCommandSuppressor은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param redisBackend 업무 처리에 필요한 redisBackend 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun inFlightCommandSuppressor(
        redisBackend: ObjectProvider<CommandSuppressionBackend>,
    ): InFlightCommandSuppressor =
        InFlightCommandSuppressor(redisBackend.ifAvailable)

    /**
     * reservationCommandExecutionGate은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param admission 업무 처리에 필요한 admission 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param suppression 업무 처리에 필요한 suppression 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param credentials 업무 처리에 필요한 credentials 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param tenantId 예약 제어면 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    @Bean
    fun reservationCommandExecutionGate(
        admission: ReservationAdmissionGate,
        suppression: InFlightCommandSuppressor,
        credentials: ReservationCredentialService,
        @Value("\${reservation.tenant-id}") tenantId: String,
    ): ReservationCommandExecutionGate =
        ReservationCommandExecutionGate(admission, suppression, credentials, tenantId)

    /**
     * reservationExpirySweeper은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param leaderElector 업무 처리에 필요한 leaderElector 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param sweepWork 업무 처리에 필요한 sweepWork 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param instanceId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    @Bean
    fun reservationExpirySweeper(
        @Qualifier("reservationLeaderElector")
        leaderElector: ObjectProvider<LeaderElector>,
        sweepWork: ReservationSweepWork,
        @Value("\${reservation.instance-id:#{T(java.util.UUID).randomUUID().toString()}}") instanceId: String,
    ): ReservationExpirySweeper? =
        leaderElector.ifAvailable?.let {
            ReservationExpirySweeper(
                leaderGate = LeaderElectorSweepGate(it),
                sweepWork = sweepWork,
                instanceId = instanceId
            )
        }
}

/**
 * Redis가 활성화되어 있고 접근 가능할 때만 Lettuce admission, suppression, leader adapter를 생성합니다.
 * 이 bean들은 advisory입니다. `null`을 반환하면 Redis outage 중에도 PostgreSQL command를 계속 사용할 수 있습니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "reservation.redis", name = ["enabled"], havingValue = "true")
internal class ReservationRedisConfiguration {

    companion object: KLogging()

    /**
     * reservationRedisClient은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param uri 예약 제어면 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    @Bean(destroyMethod = "shutdown")
    fun reservationRedisClient(
        @Value("\${reservation.redis.uri}") uri: String,
    ): RedisClient =
        LettuceClients.clientOf(uri)
            .also { log.info { "reservation_redis_client_created" } }

    /**
     * reservationRedisConnection은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param client 업무 처리에 필요한 client 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean(destroyMethod = "close")
    fun reservationRedisConnection(client: RedisClient): StatefulRedisConnection<String, String>? =
        try {
            LettuceClients.connect(client)
        } catch (_: Exception) {
            log.warn { "reservation_redis_connection_failed fallback=POSTGRES" }
            null
        }

    /**
     * reservationAdmissionBackend은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun reservationAdmissionBackend(
        connection: ObjectProvider<StatefulRedisConnection<String, String>>,
    ): AdmissionPermitBackend? =
        connection.ifAvailable?.let {
            LettuceSemaphoreAdmissionBackend(it)
        }

    /**
     * reservationSuppressionBackend은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun reservationSuppressionBackend(
        connection: ObjectProvider<StatefulRedisConnection<String, String>>,
    ): CommandSuppressionBackend? =
        connection.ifAvailable?.let {
            LettuceLockSuppressionBackend(it)
        }

    /**
     * reservationLeaderElector은 예약 제어면 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param connection 업무 처리에 필요한 connection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun reservationLeaderElector(
        connection: ObjectProvider<StatefulRedisConnection<String, String>>,
    ): LeaderElector? =
        connection.ifAvailable?.let {
            LettuceLeaderElector(
                connection = it,
                options = LeaderElectionOptions(
                    waitTime = kotlin.time.Duration.ZERO,
                    leaseTime = 15.seconds,
                    autoExtend = true
                )
            )
        }
}

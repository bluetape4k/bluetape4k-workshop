package io.bluetape4k.workshop.commerce.reservation.config

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import javax.sql.DataSource
import kotlin.time.toKotlinDuration

/** UTC clock, Exposed transaction manager, HMAC service, Java 25 virtual-thread executor를 제공합니다. */
/** UTC clock, Exposed transaction manager, HMAC service, Java 25 virtual-thread executor를 제공합니다. */
@Configuration(proxyBeanMethods = false)
internal class ReservationConfiguration {

    companion object: KLogging()

    @Bean
    /**
     * clock은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun clock(): Clock = Clock.systemUTC()

    @Bean("springTransactionManager")
    /**
     * springTransactionManager은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param dataSource 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun springTransactionManager(dataSource: DataSource): PlatformTransactionManager =
        SpringTransactionManager(dataSource, DatabaseConfig {}, false)

    @Bean
    /**
     * reservationCredentialService은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun reservationCredentialService(
        @Value("\${reservation.security.hmac-secret}") secret: String,
    ): ReservationCredentialService =
        ReservationCredentialService(secret)

    @Bean(destroyMethod = "")
    /**
     * reservationExecutor은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun reservationExecutor(): ExecutorService =
        VirtualThreads.executorService()
            .also {
                log.info { "reservation_executor_created virtualThreads=true" }
            }

    @Bean
    /**
     * reservationExecutorShutdown은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param executor 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun reservationExecutorShutdown(executor: ExecutorService): ExecutorShutdown =
        ExecutorShutdown(executor)
}

/** cancellation을 강제하기 전에 virtual-thread executor에 bounded graceful shutdown을 적용합니다. */
/** cancellation을 강제하기 전에 virtual-thread executor에 bounded graceful shutdown을 적용합니다. */
internal class ExecutorShutdown(
    /**
     * `executor`은 예약 제어면에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService,
    /**
     * `timeout`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val timeout: Duration = 10.seconds(),
): AutoCloseable {

    companion object: KLogging()

    /**
     * close은 예약 제어면 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toKotlinDuration())) {
                log.warn { "reservation_executor_forced_shutdown timeoutMillis=${timeout.toMillis()}" }
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info { "reservation_executor_shutdown" }
    }
}

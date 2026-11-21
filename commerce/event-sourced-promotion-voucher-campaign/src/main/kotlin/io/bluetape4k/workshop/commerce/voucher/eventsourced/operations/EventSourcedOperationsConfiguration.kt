package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.jdbc.sql.singleString
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventSourcedExposedDatabaseRegistration
import io.micrometer.core.instrument.MeterRegistry
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/** bounded database admission gate와 low-cardinality operational meter를 연결합니다. */
/** bounded database admission gate와 low-cardinality operational meter를 연결합니다. */
@Configuration(proxyBeanMethods = false)
internal class EventSourcedOperationsConfiguration {

    companion object: KLogging()

    /**
     * eventSourcedMetrics은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registry 업무 처리에 필요한 registry 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventSourcedMetrics(registry: MeterRegistry): EventSourcedMetrics =
        EventSourcedMetrics(registry)

    /**
     * eventSourcedDatabasePermitGate은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventSourcedDatabasePermitGate(metrics: EventSourcedMetrics): EventSourcedDatabasePermitGate =
        EventSourcedDatabasePermitGate(metrics = metrics).also(metrics::bind)

    /**
     * eventSourcedHikariMetricsBinding은 프로모션 바우처 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록
     * 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param metrics 업무 처리에 필요한 metrics 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean
    fun eventSourcedHikariMetricsBinding(
        metrics: EventSourcedMetrics,
        dataSource: DataSource,
    ): EventSourcedHikariMetricsBinding {
        val hikari = (dataSource as? HikariDataSource).checkNotNull { "event-sourced datasource must use HikariCP" }
        metrics.bind(hikari)
        return EventSourcedHikariMetricsBinding
    }

    /**
     * eventSourcedStartupProbe은 프로모션 바우처 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param registration 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param permits 업무 처리에 필요한 permits 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventSourcedStartupProbe(
        registration: EventSourcedExposedDatabaseRegistration,
        permits: EventSourcedDatabasePermitGate,
    ): EventSourcedStartupProbe =
        EventSourcedPermitTransactionRunner(
            database = registration.database,
            permits = permits,
            lane = EventSourcedDatabaseLane.READINESS,
        ).let { transactions ->
            EventSourcedStartupProbe {
                transactions.inTransaction {
                    /**
                     * `transaction`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
                     *
                     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
                     */
                    val transaction = TransactionManager.current()
                    transaction.exec("SELECT 1")
                    /**
                     * `authorityAvailable`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
                     *
                     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
                     */
                    val authorityAvailable = transaction
                        .exec("SELECT to_regclass('voucher_event_log')") { result ->
                            result.singleString() != null
                        } ?: false
                    check(authorityAvailable) { "voucher_event_log schema is unavailable" }
                }
            }
        }
}

/**
 * EventSourcedHikariMetricsBinding은 이벤트 소싱 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을
 * 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal data object EventSourcedHikariMetricsBinding

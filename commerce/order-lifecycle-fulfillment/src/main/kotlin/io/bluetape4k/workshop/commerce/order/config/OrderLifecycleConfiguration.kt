package io.bluetape4k.workshop.commerce.order.config

import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ExecutorService
import kotlin.time.toKotlinDuration

@Configuration(proxyBeanMethods = false)
/**
 * OrderLifecycleConfiguration은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class OrderLifecycleConfiguration {
    /**
     * clock은 주문 라이프사이클과 fulfillment의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를
     * 거절합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * orderLifecycleExecutor은 주문 라이프사이클과 fulfillment 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에
     * 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean(destroyMethod = "")
    fun orderLifecycleExecutor(): ExecutorService =
        VirtualThreads.executorService().also {
            log.info { "order_lifecycle_executor_created virtualThreads=true" }
        }

    /**
     * orderLifecycleExecutorShutdown은 주문 라이프사이클과 fulfillment 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무
     * 처리 전에
     * 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param executor 업무 처리에 필요한 executor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun orderLifecycleExecutorShutdown(executor: ExecutorService) = ExecutorShutdown(executor)

    companion object: KLogging()
}
/**
 * ExecutorShutdown은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property executor 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property timeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */


internal class ExecutorShutdown(
    /**
     * `executor`은 주문·결제·재고·배송에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val executor: ExecutorService,
    /**
     * `timeout`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val timeout: Duration = 10.seconds(),
): AutoCloseable {

    companion object: KLogging()

    /**
     * close은 주문 라이프사이클과 fulfillment의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeout.toKotlinDuration())) {
                log.warn { "order_lifecycle_executor_forced_shutdown timeoutMillis=${timeout.toMillis()}" }
                executor.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        log.info { "order_lifecycle_executor_shutdown" }
    }
}

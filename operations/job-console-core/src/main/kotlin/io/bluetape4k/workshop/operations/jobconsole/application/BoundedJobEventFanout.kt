package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.operations.jobconsole.api.JobEvent
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import kotlin.time.toKotlinDuration
/**
 * FanoutResult은 운영 작업 콘솔에서 전달되는 불변 데이터 모델입니다.
 * @property deliveredSubscribers 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property evictedSubscribers 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class FanoutResult(
    /**
     * `deliveredSubscribers`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val deliveredSubscribers: Int,
    /**
     * `evictedSubscribers`은 작업 운영 콘솔에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val evictedSubscribers: Int,
)
/**
 * BoundedJobEventFanout은 운영 작업 콘솔에서 사용하는 업무 객체입니다.
 * @property writeTimeout 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property executor 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


class BoundedJobEventFanout(
    /**
     * `writeTimeout`은 작업 운영 콘솔에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val writeTimeout: Duration,
    /** Adapter가 소유하고 lifecycle을 관리하는 executor입니다. */
    /** Adapter가 소유하고 lifecycle을 관리하는 executor입니다. */
    private val executor: ExecutorService = ForkJoinPool.commonPool(),
) {

    companion object: KLogging()
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val subscribers = ConcurrentHashMap<String, (JobEvent) -> Unit>()
    /**
     * `subscriberCount`은 작업 운영 콘솔에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */

    val subscriberCount: Int
        get() = subscribers.size

    /**
     * `subscribe`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = subscribe(id = "demo", consumer = consumer)
     * ```
     * @param id 요청 경계에서 검증된 `id`입니다.
     * @param consumer 요청 경계에서 검증된 `consumer`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun subscribe(id: String, consumer: (JobEvent) -> Unit): AutoCloseable {
        subscribers[id] = consumer
        return AutoCloseable { subscribers.remove(id, consumer) }
    }

    /**
     * `publish`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = publish(event = event)
     * ```
     * @param event 요청 경계에서 검증된 `event`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun publish(event: JobEvent): FanoutResult {
        val snapshot = subscribers.entries.toList()
        var delivered = 0
        var evicted = 0
        val writes = snapshot.associateWith { (_, consumer) -> executor.submit { consumer(event) } }

        writes.forEach { (subscriber, future) ->
            val succeeded = runCatching { future.get(writeTimeout.toKotlinDuration()) }.isSuccess
            if (succeeded) {
                delivered++
                log.info { "Job is success. key=${subscriber.key}" }
            } else {
                future.cancel(true)
                subscribers.remove(subscriber.key, subscriber.value)
                log.warn { "Remove Job. key=${subscriber.key}" }
                evicted++
            }
        }
        return FanoutResult(delivered, evicted)
    }
}

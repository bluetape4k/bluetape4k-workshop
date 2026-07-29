package io.bluetape4k.workshop.aws.eventbridge

import java.io.Serializable
import java.time.Instant

/**
 * 워크숍이 EventBridge detail 필드로 발행하는 처리 흐름 유형입니다.
 */
enum class OrderWorkflow {
    PAYMENT_REMINDER,
    FULFILLMENT_CHECK,
}

/**
 * 로컬 EventBridge와 Scheduler 어댑터가 반환하는 경계 상태입니다.
 */
enum class BoundaryState {
    PUBLISHED,
    FAILED,
    SKIPPED,
}

/**
 * EventBridge Scheduler 예제로 주문 처리 흐름을 시작하는 명령입니다.
 *
 * 멱등성 키는 스케줄 이름으로 재사용하고, correlation id는
 * EventBridge 추적 헤더와 Scheduler 요청에 모두 복사합니다.
 */
data class OrderWorkflowRequest(
    val orderId: String,
    val customerId: String,
    val workflow: OrderWorkflow,
    val scheduledAt: Instant,
    val idempotencyKey: String,
    val correlationId: String,
    val reason: String = "",
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 863019095481395421L
    }
}

/**
 * 경계 호출의 상태 객체입니다.
 */
data class BoundaryStatus(
    val state: BoundaryState,
    val message: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -5558132079675700722L

        /**
         * 성공한 경계 상태를 만듭니다.
         */
        fun published(message: String = "published"): BoundaryStatus =
            BoundaryStatus(BoundaryState.PUBLISHED, message)

        /**
         * 예외 메시지로 실패한 경계 상태를 만듭니다.
         */
        fun failed(error: Throwable): BoundaryStatus =
            BoundaryStatus(BoundaryState.FAILED, error.message ?: error::class.java.simpleName)

        /**
         * 이전 경계가 실패했을 때 건너뛴 경계 상태를 만듭니다.
         */
        fun skipped(message: String): BoundaryStatus =
            BoundaryStatus(BoundaryState.SKIPPED, message)
    }
}

/**
 * EventBridge Scheduler로 보낼 요청의 로컬 표현입니다.
 */
data class SchedulerWorkflowRequest(
    val name: String,
    val groupName: String,
    val targetArn: String,
    val scheduleExpression: String,
    val scheduleExpressionTimezone: String,
    val payloadJson: String,
    val flexibleTimeWindowMode: String,
    val idempotencyKey: String,
    val correlationId: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 4285108231899947190L
    }
}

/**
 * 예제가 두 경계를 모두 시도한 뒤 학습자에게 반환하는 결과입니다.
 */
data class OrderWorkflowReport(
    val eventBridge: BoundaryStatus,
    val scheduler: BoundaryStatus,
    val idempotencyKey: String,
    val correlationId: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 9176396687850281355L
    }
}

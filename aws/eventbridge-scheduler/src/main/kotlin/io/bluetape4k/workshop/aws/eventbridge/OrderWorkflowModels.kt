package io.bluetape4k.workshop.aws.eventbridge

import java.io.Serializable
import java.time.Instant

/**
 * Workflow types that the workshop publishes as EventBridge detail fields.
 */
enum class OrderWorkflow {
    PAYMENT_REMINDER,
    FULFILLMENT_CHECK,
}

/**
 * Boundary state returned from the local EventBridge and Scheduler adapters.
 */
enum class BoundaryState {
    PUBLISHED,
    FAILED,
    SKIPPED,
}

/**
 * Command for starting an order workflow through the EventBridge Scheduler example.
 *
 * The idempotency key is reused as the schedule name, and the correlation id is
 * copied to both the EventBridge trace header and the Scheduler request.
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
 * Status object for a boundary call.
 */
data class BoundaryStatus(
    val state: BoundaryState,
    val message: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -5558132079675700722L

        /**
         * Builds a successful boundary status.
         */
        fun published(message: String = "published"): BoundaryStatus =
            BoundaryStatus(BoundaryState.PUBLISHED, message)

        /**
         * Builds a failed boundary status from an exception message.
         */
        fun failed(error: Throwable): BoundaryStatus =
            BoundaryStatus(BoundaryState.FAILED, error.message ?: error::class.java.simpleName)

        /**
         * Builds a skipped boundary status when a previous boundary failed.
         */
        fun skipped(message: String): BoundaryStatus =
            BoundaryStatus(BoundaryState.SKIPPED, message)
    }
}

/**
 * Local representation of the request that would be sent to EventBridge Scheduler.
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
 * Result returned to learners after the example attempts both boundaries.
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

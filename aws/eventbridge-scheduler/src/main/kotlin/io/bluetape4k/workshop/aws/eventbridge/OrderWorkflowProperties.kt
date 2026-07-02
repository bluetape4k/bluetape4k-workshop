package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable

/**
 * Configuration used to map order workflows to EventBridge and Scheduler fields.
 */
@ConfigurationProperties(prefix = "bluetape4k.aws.eventbridge-scheduler")
data class OrderWorkflowProperties(
    val source: String = "bluetape4k.workshop.orders",
    val detailType: String = "OrderWorkflowRequested",
    val eventBusName: String = "workshop-events",
    val schedulerGroupName: String = "order-workflows",
    val schedulerTargetArn: String = "arn:aws:lambda:ap-northeast-2:123456789012:function:order-reminder",
    val flexibleTimeWindowMode: String = "OFF",
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -5979974063158898606L
    }
}

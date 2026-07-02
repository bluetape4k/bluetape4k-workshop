package io.bluetape4k.workshop.aws.sqssns

import org.springframework.stereotype.Component

/**
 * Application handler invoked after SQS delivers an order notification.
 */
fun interface OrderNotificationHandler {

    /**
     * Handles one decoded notification event.
     */
    suspend fun handle(event: OrderNotificationEvent)
}

/**
 * Default local handler for `bootRun`; tests and real applications can replace it.
 */
@Component
class NoopOrderNotificationHandler: OrderNotificationHandler {

    override suspend fun handle(event: OrderNotificationEvent) = Unit
}

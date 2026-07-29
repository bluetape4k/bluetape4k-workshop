package io.bluetape4k.workshop.aws.sqssns

import org.springframework.stereotype.Component

/**
 * SQS가 주문 알림을 전달한 뒤 호출되는 애플리케이션 핸들러입니다.
 */
fun interface OrderNotificationHandler {

    /**
     * 디코딩된 알림 이벤트 하나를 처리합니다.
     */
    suspend fun handle(event: OrderNotificationEvent)
}

/**
 * 기본 `bootRun`용 로컬 핸들러이며, 테스트와 실제 애플리케이션에서 대체할 수 있습니다.
 */
@Component
class NoopOrderNotificationHandler: OrderNotificationHandler {

    override suspend fun handle(event: OrderNotificationEvent) = Unit
}

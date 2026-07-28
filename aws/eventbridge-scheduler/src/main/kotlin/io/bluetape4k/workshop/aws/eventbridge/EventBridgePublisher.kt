package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.stereotype.Component
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * EventBridge 항목을 발행합니다.
 *
 * 워크숍 기본 구현은 항목을 로컬에 캡처하므로 예제를
 * AWS 자격 증명 없이 테스트할 수 있습니다. 실제 어댑터는 AWS
 * SDK 클라이언트에 위임하되 이 계약을 유지할 수 있습니다.
 */
interface EventBridgePublisher {
    /**
     * 하나 이상의 EventBridge 항목을 발행하고 경계 상태를 반환합니다.
     */
    suspend fun publish(entries: List<PutEventsRequestEntry>): BoundaryStatus
}

/**
 * 로컬 워크숍 실행용 인메모리 EventBridge 발행자입니다.
 */
@Component
class LocalEventBridgePublisher : EventBridgePublisher {

    private val publishedEntries = CopyOnWriteArrayList<PutEventsRequestEntry>()

    override suspend fun publish(entries: List<PutEventsRequestEntry>): BoundaryStatus {
        publishedEntries += entries
        return BoundaryStatus.published("captured ${entries.size} EventBridge event(s) locally")
    }

    /**
     * 캡처한 EventBridge 항목의 안정적인 복사본을 반환합니다.
     */
    fun snapshot(): List<PutEventsRequestEntry> =
        publishedEntries.toList()
}

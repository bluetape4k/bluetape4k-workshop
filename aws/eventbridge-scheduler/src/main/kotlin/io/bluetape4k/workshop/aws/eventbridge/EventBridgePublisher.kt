package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.stereotype.Component
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Publishes EventBridge entries.
 *
 * The workshop default implementation captures entries locally so the example
 * can be tested without AWS credentials. A real adapter can delegate to the AWS
 * SDK client while preserving this contract.
 */
interface EventBridgePublisher {
    /**
     * Publishes one or more EventBridge entries and returns a boundary status.
     */
    suspend fun publish(entries: List<PutEventsRequestEntry>): BoundaryStatus
}

/**
 * In-memory EventBridge publisher for local workshop runs.
 */
@Component
class LocalEventBridgePublisher : EventBridgePublisher {

    private val publishedEntries = CopyOnWriteArrayList<PutEventsRequestEntry>()

    override suspend fun publish(entries: List<PutEventsRequestEntry>): BoundaryStatus {
        publishedEntries += entries
        return BoundaryStatus.published("captured ${entries.size} EventBridge event(s) locally")
    }

    /**
     * Returns a stable copy of captured EventBridge entries.
     */
    fun snapshot(): List<PutEventsRequestEntry> =
        publishedEntries.toList()
}

package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import java.time.Duration

data class OutboxPollResult(
    val claimed: Int,
    val published: Int,
)

class JobOutboxPoller(
    private val repository: JobOutboxRepository,
    private val fanout: BoundedJobEventFanout,
    private val batchSize: Int = 25,
    private val claimDuration: Duration = Duration.ofSeconds(30),
) {
    fun pollOnce(): OutboxPollResult {
        val claim = repository.claim(batchSize, claimDuration)
        var published = 0
        claim.events.forEach { event ->
            runCatching { fanout.publish(event) }
                .onSuccess {
                    if (repository.markPublished(claim.token, event.eventId)) published++
                }
                .onFailure { repository.release(claim.token, event.eventId) }
        }
        return OutboxPollResult(claim.events.size, published)
    }
}

package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Schedules delayed workflow invocations.
 */
interface WorkflowScheduler {
    /**
     * Schedules a workflow request and returns a boundary status.
     */
    suspend fun schedule(request: SchedulerWorkflowRequest): BoundaryStatus
}

/**
 * In-memory Scheduler adapter for local workshop runs.
 */
@Component
class LocalWorkflowScheduler : WorkflowScheduler {

    private val scheduledRequests = CopyOnWriteArrayList<SchedulerWorkflowRequest>()

    override suspend fun schedule(request: SchedulerWorkflowRequest): BoundaryStatus {
        scheduledRequests += request
        return BoundaryStatus.published("captured schedule ${request.name} locally")
    }

    /**
     * Returns a stable copy of captured schedule requests.
     */
    fun snapshot(): List<SchedulerWorkflowRequest> =
        scheduledRequests.toList()
}

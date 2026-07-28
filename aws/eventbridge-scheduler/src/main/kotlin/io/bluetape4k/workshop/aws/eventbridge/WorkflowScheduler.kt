package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 지연된 처리 흐름 호출을 예약합니다.
 */
interface WorkflowScheduler {
    /**
     * 처리 흐름 요청을 예약하고 경계 상태를 반환합니다.
     */
    suspend fun schedule(request: SchedulerWorkflowRequest): BoundaryStatus
}

/**
 * 로컬 워크숍 실행용 인메모리 Scheduler 어댑터입니다.
 */
@Component
class LocalWorkflowScheduler : WorkflowScheduler {

    private val scheduledRequests = CopyOnWriteArrayList<SchedulerWorkflowRequest>()

    override suspend fun schedule(request: SchedulerWorkflowRequest): BoundaryStatus {
        scheduledRequests += request
        return BoundaryStatus.published("captured schedule ${request.name} locally")
    }

    /**
     * 캡처한 스케줄 요청의 안정적인 복사본을 반환합니다.
     */
    fun snapshot(): List<SchedulerWorkflowRequest> =
        scheduledRequests.toList()
}

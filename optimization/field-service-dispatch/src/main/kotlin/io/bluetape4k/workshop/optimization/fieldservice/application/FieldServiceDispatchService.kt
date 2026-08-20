package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.WorkerId
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.RouteCommitResult
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** 승인된 proposal에서 한 worker route만 원자적으로 확정합니다. */
class FieldServiceDispatchService(
    private val repository: FieldServiceRepository,
) {
    fun confirmWorkerRoute(workerId: WorkerId, planId: PlanId, revision: Long): DispatchResult = transaction {
        when (repository.commitWorkerRoute(workerId, planId, revision)) {
            RouteCommitResult.COMMITTED -> DispatchResult.COMMITTED
            RouteCommitResult.VERSION_CONFLICT -> DispatchResult.VERSION_CONFLICT
            RouteCommitResult.SCHEDULE_CONFLICT -> DispatchResult.SCHEDULE_CONFLICT
        }
    }
}

enum class DispatchResult {
    COMMITTED,
    VERSION_CONFLICT,
    SCHEDULE_CONFLICT,
}

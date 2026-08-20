package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Proposal 승인 단계의 version-vector CAS를 담당하는 서비스입니다. */
class FieldServiceApprovalService(
    private val repository: FieldServiceRepository,
) {
    fun approve(planId: PlanId, revision: Long, expected: VersionVector): ApprovalResult = transaction {
        val plan = repository.loadPlan(planId, revision)
        if (plan == null || plan.versionVector != expected) {
            repository.appendAudit("plan", planId.value, "VERSION_CONFLICT", revision, "version_vector_conflict")
            return@transaction ApprovalResult.VERSION_CONFLICT
        }
        if (plan.state != io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState.DRAFT) {
            repository.appendAudit("plan", planId.value, "VERSION_CONFLICT", revision, "plan_not_draft")
            return@transaction ApprovalResult.VERSION_CONFLICT
        }
        if (!repository.lockVersionVector(expected) || !repository.updatePlanStateIfDraft(plan, io.bluetape4k.workshop.optimization.fieldservice.domain.PlanState.APPROVED)) {
            repository.appendAudit("plan", planId.value, "VERSION_CONFLICT", revision, "version_vector_conflict")
            return@transaction ApprovalResult.VERSION_CONFLICT
        }
        repository.appendAudit("plan", planId.value, "APPROVED", revision, "proposal_approved")
        ApprovalResult.APPROVED
    }
}

enum class ApprovalResult {
    APPROVED,
    VERSION_CONFLICT,
}

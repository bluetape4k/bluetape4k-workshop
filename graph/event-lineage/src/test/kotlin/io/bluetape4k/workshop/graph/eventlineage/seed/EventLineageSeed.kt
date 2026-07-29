package io.bluetape4k.workshop.graph.eventlineage.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.eventlineage.service.EventLineageService
import java.io.Serializable

/**
 * [seedEventLineage]가 생성한 정점입니다.
 *
 * 토폴로지:
 * ```
 * order-1001 ──EMITS──► order-created
 * order-1001 ──EMITS──► risk-scored ──CAUSED_BY──► order-created
 * order-1001 ──EMITS──► discount-requested ──CAUSED_BY──► risk-scored
 * order-1001 ──EMITS──► manager-approved ──CAUSED_BY──► discount-requested
 * order-1001 ──EMITS──► order-approved ──CAUSED_BY──► manager-approved
 * order-approved ──APPROVED_BY──► decision-discount-approval ──DECIDED_BY──► manager-maria
 * order-1001 ──EMITS──► order-approval-corrected ──SUPERSEDES──► order-approved
 * order-1001 ──EMITS──► manual-note-added  (intentionally missing cause)
 * ```
 */
data class EventLineageSeed(
    val orderAggregate: GraphVertex,
    val orderCreated: GraphVertex,
    val riskScored: GraphVertex,
    val discountRequested: GraphVertex,
    val managerApproved: GraphVertex,
    val orderApproved: GraphVertex,
    val orderApprovalCorrected: GraphVertex,
    val manualNoteAdded: GraphVertex,
    val managerApproval: GraphVertex,
    val managerActor: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 결정적인 주문 승인 lineage 시나리오를 채웁니다.
 */
fun seedEventLineage(service: EventLineageService): EventLineageSeed {
    val orderAggregate = service.addAggregate(
        aggregateId = "order-1001",
        aggregateType = "Order",
        state = "APPROVED",
        version = 4,
    )

    val orderCreated = service.addEvent(
        eventId = "order-created",
        type = "OrderCreated",
        occurredAt = "2026-07-02T01:00:00Z",
        summary = "Customer submitted the order.",
    )
    val riskScored = service.addEvent(
        eventId = "risk-scored",
        type = "RiskScored",
        occurredAt = "2026-07-02T01:01:00Z",
        summary = "Risk engine assigned a medium score.",
    )
    val discountRequested = service.addEvent(
        eventId = "discount-requested",
        type = "DiscountRequested",
        occurredAt = "2026-07-02T01:02:00Z",
        summary = "Customer requested a manual discount.",
    )
    val managerApproved = service.addEvent(
        eventId = "manager-approved",
        type = "ManagerApproved",
        occurredAt = "2026-07-02T01:03:00Z",
        summary = "Manager approved the discount.",
    )
    val orderApproved = service.addEvent(
        eventId = "order-approved",
        type = "OrderApproved",
        occurredAt = "2026-07-02T01:04:00Z",
        summary = "Order moved to approved state.",
    )
    val orderApprovalCorrected = service.addEvent(
        eventId = "order-approval-corrected",
        type = "OrderApprovalCorrected",
        occurredAt = "2026-07-02T01:05:00Z",
        summary = "Approval event metadata was corrected.",
    )
    val manualNoteAdded = service.addEvent(
        eventId = "manual-note-added",
        type = "ManualNoteAdded",
        occurredAt = "2026-07-02T01:06:00Z",
        summary = "Support added a manual note without lineage evidence.",
    )

    val managerActor = service.addActor(
        actorId = "manager-maria",
        displayName = "Maria Manager",
        role = "Approver",
    )
    val managerApproval = service.addDecision(
        decisionId = "decision-discount-approval",
        decisionType = "DiscountApproval",
        status = "APPROVED",
        reason = "Manual review accepted the medium-risk discount.",
    )

    listOf(
        orderCreated,
        riskScored,
        discountRequested,
        managerApproved,
        orderApproved,
        orderApprovalCorrected,
        manualNoteAdded,
    ).forEach { event ->
        service.emit(orderAggregate.id, event.id)
    }

    service.causedBy(riskScored.id, orderCreated.id)
    service.causedBy(discountRequested.id, riskScored.id)
    service.causedBy(managerApproved.id, discountRequested.id)
    service.causedBy(orderApproved.id, managerApproved.id)
    service.approvedBy(orderApproved.id, managerApproval.id)
    service.decidedBy(managerApproval.id, managerActor.id)
    service.supersedes(orderApprovalCorrected.id, orderApproved.id)

    return EventLineageSeed(
        orderAggregate = orderAggregate,
        orderCreated = orderCreated,
        riskScored = riskScored,
        discountRequested = discountRequested,
        managerApproved = managerApproved,
        orderApproved = orderApproved,
        orderApprovalCorrected = orderApprovalCorrected,
        manualNoteAdded = manualNoteAdded,
        managerApproval = managerApproval,
        managerActor = managerActor,
    )
}

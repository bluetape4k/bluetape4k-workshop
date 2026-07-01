package io.bluetape4k.workshop.exposed.javers.approval.model

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Approval proposal and decision record.
 */
object PolicyProposalTable: LongIdTable("policy_proposals") {
    val policyId = long("policy_id").index()
    val requester = varchar("requester", 100)
    val proposalStatus = enumerationByName("proposal_status", 16, ProposalStatus::class)
    val reviewer = varchar("reviewer", 100).nullable()
    val reason = varchar("reason", 500).nullable()

    val proposedTitle = varchar("proposed_title", 255)
    val proposedStatus = enumerationByName("proposed_status", 16, PolicyStatus::class)
    val proposedCurrency = varchar("proposed_currency", 3)
    val proposedAmount = decimal("proposed_amount", precision = 19, scale = 2)
    val proposedApprovalLimit = decimal("proposed_approval_limit", precision = 19, scale = 2)
    val proposedOwner = varchar("proposed_owner", 100)

    val changedFields = text("changed_fields")
    val currentSnapshot = text("current_snapshot")
    val proposedSnapshot = text("proposed_snapshot")
}

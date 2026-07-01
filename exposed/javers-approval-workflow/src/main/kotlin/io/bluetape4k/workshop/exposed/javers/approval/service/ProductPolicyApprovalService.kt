package io.bluetape4k.workshop.exposed.javers.approval.service

import io.bluetape4k.javers.diff.changesByType
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.exposed.javers.approval.model.ChangedField
import io.bluetape4k.workshop.exposed.javers.approval.model.PolicyProposal
import io.bluetape4k.workshop.exposed.javers.approval.model.PolicyProposalTable
import io.bluetape4k.workshop.exposed.javers.approval.model.PricingPolicy
import io.bluetape4k.workshop.exposed.javers.approval.model.ProductPolicy
import io.bluetape4k.workshop.exposed.javers.approval.model.ProductPolicyTable
import io.bluetape4k.workshop.exposed.javers.approval.model.ProposalStatus
import org.javers.core.Javers
import org.javers.core.diff.changetype.ValueChange
import org.javers.core.metamodel.`object`.CdoSnapshot
import org.javers.repository.jql.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Approval workflow service that computes JaVers diffs before committing state.
 *
 * ## Behavior / Contract
 * - [publishInitial] creates the first approved current row and JaVers snapshot.
 * - [submitProposal] stores a pending proposal and returns a pre-commit diff.
 * - [approveProposal] commits the proposed aggregate and updates the current row.
 * - [rejectProposal] records the decision without creating a JaVers snapshot.
 */
class ProductPolicyApprovalService(
    private val javers: Javers,
) {
    companion object: KLogging()

    /**
     * Publishes the first approved policy state.
     */
    fun publishInitial(author: String, policy: ProductPolicy) {
        author.requireNotBlank("author")
        validatePolicy(policy)
        javers.commit(author, policy)
        upsertCurrentPolicy(policy)
        log.debug { "Published initial product policy id=${policy.id} by $author" }
    }

    /**
     * Stores a pending proposal and returns its pre-commit diff summary.
     */
    fun submitProposal(requester: String, proposed: ProductPolicy): PolicyProposal {
        requester.requireNotBlank("requester")
        validatePolicy(proposed)
        val current = requireNotNull(findCurrentPolicy(proposed.id)) {
            "Current product policy ${proposed.id} does not exist"
        }
        val changedFields = javers.compare(current, proposed)
            .changesByType<ValueChange>()
            .map { it.toChangedField() }
            .sortedBy { it.path }

        val proposalId = transaction {
            PolicyProposalTable.insertAndGetId {
                it[policyId] = proposed.id
                it[PolicyProposalTable.requester] = requester
                it[proposalStatus] = ProposalStatus.PENDING
                it[reviewer] = null
                it[reason] = null
                it[proposedTitle] = proposed.title
                it[proposedStatus] = proposed.status
                it[proposedCurrency] = proposed.pricing.currency
                it[proposedAmount] = proposed.pricing.amount
                it[proposedApprovalLimit] = proposed.pricing.approvalLimit
                it[proposedOwner] = proposed.owner
                it[PolicyProposalTable.changedFields] = changedFields.encode()
                it[currentSnapshot] = current.toJson()
                it[proposedSnapshot] = proposed.toJson()
            }.value
        }

        log.debug { "Submitted product policy proposal id=$proposalId for policy id=${proposed.id}" }
        return requireNotNull(findProposal(proposalId))
    }

    /**
     * Approves a pending proposal and commits the proposed aggregate to JaVers.
     */
    fun approveProposal(reviewer: String, proposalId: Long, reason: String): PolicyProposal {
        reviewer.requireNotBlank("reviewer")
        reason.requireNotBlank("reason")
        val proposal = requirePendingProposal(proposalId)

        javers.commit(reviewer, proposal.proposed)
        upsertCurrentPolicy(proposal.proposed)
        updateDecision(proposalId, ProposalStatus.APPROVED, reviewer, reason)
        log.debug { "Approved product policy proposal id=$proposalId by $reviewer" }
        return requireNotNull(findProposal(proposalId))
    }

    /**
     * Rejects a pending proposal without changing the current row or JaVers history.
     */
    fun rejectProposal(reviewer: String, proposalId: Long, reason: String): PolicyProposal {
        reviewer.requireNotBlank("reviewer")
        reason.requireNotBlank("reason")
        requirePendingProposal(proposalId)

        updateDecision(proposalId, ProposalStatus.REJECTED, reviewer, reason)
        log.debug { "Rejected product policy proposal id=$proposalId by $reviewer" }
        return requireNotNull(findProposal(proposalId))
    }

    /**
     * Finds the current approved policy row.
     */
    fun findCurrentPolicy(policyId: Long): ProductPolicy? =
        transaction {
            ProductPolicyTable.selectAll()
                .where { ProductPolicyTable.id eq policyId }
                .singleOrNull()
                ?.toProductPolicy()
        }

    /**
     * Finds a stored proposal and decision.
     */
    fun findProposal(proposalId: Long): PolicyProposal? =
        transaction {
            PolicyProposalTable.selectAll()
                .where { PolicyProposalTable.id eq proposalId }
                .singleOrNull()
                ?.toPolicyProposal()
        }

    /**
     * Returns approved JaVers snapshots only, oldest-first.
     */
    fun getHistory(policyId: Long): List<CdoSnapshot> {
        val query = QueryBuilder.byInstanceId(policyId, ProductPolicy::class.java)
            .build()
        return javers.findSnapshots(query)
            .sortedBy { it.commitMetadata.commitDate }
    }

    private fun upsertCurrentPolicy(policy: ProductPolicy) {
        transaction {
            ProductPolicyTable.upsert {
                it[id] = policy.id
                it[title] = policy.title
                it[status] = policy.status
                it[pricingCurrency] = policy.pricing.currency
                it[pricingAmount] = policy.pricing.amount
                it[pricingApprovalLimit] = policy.pricing.approvalLimit
                it[owner] = policy.owner
            }
        }
    }

    private fun requirePendingProposal(proposalId: Long): PolicyProposal {
        val proposal = requireNotNull(findProposal(proposalId)) {
            "Policy proposal $proposalId does not exist"
        }
        require(proposal.status == ProposalStatus.PENDING) {
            "Policy proposal $proposalId is already ${proposal.status}"
        }
        return proposal
    }

    private fun updateDecision(proposalId: Long, status: ProposalStatus, reviewer: String, reason: String) {
        transaction {
            PolicyProposalTable.update({ PolicyProposalTable.id eq proposalId }) {
                it[proposalStatus] = status
                it[PolicyProposalTable.reviewer] = reviewer
                it[PolicyProposalTable.reason] = reason
            }
        }
    }

    private fun validatePolicy(policy: ProductPolicy) {
        policy.title.requireNotBlank("policy.title")
        policy.pricing.currency.requireNotBlank("policy.pricing.currency")
        policy.owner.requireNotBlank("policy.owner")
    }

    private fun ResultRow.toProductPolicy(): ProductPolicy =
        ProductPolicy(
            id = this[ProductPolicyTable.id],
            title = this[ProductPolicyTable.title],
            status = this[ProductPolicyTable.status],
            pricing = PricingPolicy(
                currency = this[ProductPolicyTable.pricingCurrency],
                amount = this[ProductPolicyTable.pricingAmount],
                approvalLimit = this[ProductPolicyTable.pricingApprovalLimit],
            ),
            owner = this[ProductPolicyTable.owner],
        )

    private fun ResultRow.toPolicyProposal(): PolicyProposal {
        val proposed = ProductPolicy(
            id = this[PolicyProposalTable.policyId],
            title = this[PolicyProposalTable.proposedTitle],
            status = this[PolicyProposalTable.proposedStatus],
            pricing = PricingPolicy(
                currency = this[PolicyProposalTable.proposedCurrency],
                amount = this[PolicyProposalTable.proposedAmount],
                approvalLimit = this[PolicyProposalTable.proposedApprovalLimit],
            ),
            owner = this[PolicyProposalTable.proposedOwner],
        )
        return PolicyProposal(
            id = this[PolicyProposalTable.id].value,
            policyId = this[PolicyProposalTable.policyId],
            requester = this[PolicyProposalTable.requester],
            status = this[PolicyProposalTable.proposalStatus],
            proposed = proposed,
            changedFields = this[PolicyProposalTable.changedFields].decodeChangedFields(),
            reviewer = this[PolicyProposalTable.reviewer],
            reason = this[PolicyProposalTable.reason],
        )
    }

    private fun ValueChange.toChangedField(): ChangedField =
        ChangedField(
            path = nestedPath(),
            left = left?.toReviewString(),
            right = right?.toReviewString(),
        )

    private fun ValueChange.nestedPath(): String {
        val globalId = affectedGlobalId.value()
        val valueObjectPath = globalId.substringAfter("#", missingDelimiterValue = "")
        return if (valueObjectPath.isBlank()) propertyName else "$valueObjectPath.$propertyName"
    }

    private fun Any.toReviewString(): String =
        when (this) {
            is BigDecimal -> setScale(2, RoundingMode.UNNECESSARY).toPlainString()
            else -> toString()
        }

    private fun List<ChangedField>.encode(): String =
        joinToString("\n") {
            listOf(it.path, it.left.orEmpty(), it.right.orEmpty())
                .joinToString("\t") { part -> part.escapeField() }
        }

    private fun String.decodeChangedFields(): List<ChangedField> =
        lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split("\t", limit = 3).map { it.unescapeField() }
                ChangedField(
                    path = parts.getOrElse(0) { "" },
                    left = parts.getOrElse(1) { "" }.ifBlank { null },
                    right = parts.getOrElse(2) { "" }.ifBlank { null },
                )
            }
            .toList()

    private fun String.escapeField(): String =
        replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")

    private fun String.unescapeField(): String =
        replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")

    private fun ProductPolicy.toJson(): String =
        """{"id":$id,"title":"${title.escapeJson()}","status":"$status","pricing":{"currency":"${pricing.currency.escapeJson()}","amount":"${pricing.amount.toReviewString()}","approvalLimit":"${pricing.approvalLimit.toReviewString()}"},"owner":"${owner.escapeJson()}"}"""

    private fun String.escapeJson(): String =
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
}

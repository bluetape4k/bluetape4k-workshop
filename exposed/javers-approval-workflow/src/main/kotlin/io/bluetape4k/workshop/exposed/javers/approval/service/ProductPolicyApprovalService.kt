package io.bluetape4k.workshop.exposed.javers.approval.service

import com.google.gson.JsonObject
import io.bluetape4k.javers.codecs.JaversCodecs
import io.bluetape4k.javers.diff.changesByType
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 상태를 commit하기 전에 JaVers diff를 계산하는 approval workflow 서비스이다.
 *
 * ## 동작 / 계약
 * - [publishInitial]은 최초 승인된 current row와 JaVers snapshot을 만든다.
 * - [submitProposal]은 pending 제안을 저장하고 commit 전 diff를 반환한다.
 * - [approveProposal]은 제안된 aggregate를 commit하고 current row를 갱신한다.
 * - [rejectProposal]은 JaVers snapshot을 만들지 않고 결정만 기록한다.
 */
class ProductPolicyApprovalService(
    private val javers: Javers,
) {
    companion object: KLogging() {
        private const val CURRENCY_CODE_LENGTH = 3
        private const val MONEY_SCALE = 2
        private const val DEFAULT_HISTORY_LIMIT = 100
        private const val MAX_HISTORY_LIMIT = 100
    }

    /**
     * 최초 승인된 policy 상태를 publish한다.
     */
    fun publishInitial(author: String, policy: ProductPolicy) {
        author.requireNotBlank("author")
        validatePolicy(policy)
        javers.commit(author, policy)
        upsertCurrentPolicy(policy)
        log.debug { "Published initial product policy id=${policy.id} by $author" }
    }

    /**
     * pending 제안을 저장하고 commit 전 diff 요약을 반환한다.
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
                it[currentSnapshot] = current.toPolicyJson()
                it[proposedSnapshot] = proposed.toPolicyJson()
            }.value
        }

        log.debug { "Submitted product policy proposal id=$proposalId for policy id=${proposed.id}" }
        return requireNotNull(findProposal(proposalId))
    }

    /**
     * pending 제안을 승인하고 제안된 aggregate를 JaVers에 commit한다.
     */
    fun approveProposal(reviewer: String, proposalId: Long, reason: String): PolicyProposal {
        reviewer.requireNotBlank("reviewer")
        reason.requireNotBlank("reason")
        val proposal = requirePendingProposal(proposalId)

        transitionPendingDecision(proposalId, ProposalStatus.APPROVED, reviewer, reason)
        javers.commit(reviewer, proposal.proposed)
        upsertCurrentPolicy(proposal.proposed)
        log.debug { "Approved product policy proposal id=$proposalId by $reviewer" }
        return requireNotNull(findProposal(proposalId))
    }

    /**
     * current row나 JaVers history를 바꾸지 않고 pending 제안을 거절한다.
     */
    fun rejectProposal(reviewer: String, proposalId: Long, reason: String): PolicyProposal {
        reviewer.requireNotBlank("reviewer")
        reason.requireNotBlank("reason")
        requirePendingProposal(proposalId)

        transitionPendingDecision(proposalId, ProposalStatus.REJECTED, reviewer, reason)
        log.debug { "Rejected product policy proposal id=$proposalId by $reviewer" }
        return requireNotNull(findProposal(proposalId))
    }

    /**
     * 현재 승인된 policy row를 찾는다.
     */
    fun findCurrentPolicy(policyId: Long): ProductPolicy? =
        transaction {
            val validPolicyId = policyId.requirePositiveNumber("policyId")
            ProductPolicyTable.selectAll()
                .where { ProductPolicyTable.id eq validPolicyId }
                .singleOrNull()
                ?.toProductPolicy()
        }

    /**
     * 저장된 제안과 결정 정보를 찾는다.
     */
    fun findProposal(proposalId: Long): PolicyProposal? =
        transaction {
            val validProposalId = proposalId.requirePositiveNumber("proposalId")
            PolicyProposalTable.selectAll()
                .where { PolicyProposalTable.id eq validProposalId }
                .singleOrNull()
                ?.toPolicyProposal()
        }

    /**
     * 승인된 JaVers snapshot을 [limit]개까지 newest-first 순서로 반환한다.
     *
     * 빈 목록은 unknown policy와 아직 audit commit이 없는 policy를 구분하지 않는다.
     * 반환 snapshot을 외부 API로 노출할 때는 호출자가 authorization과 redaction을 적용해야 한다.
     */
    @JvmOverloads
    fun getHistory(
        policyId: Long,
        limit: Int = DEFAULT_HISTORY_LIMIT,
    ): List<CdoSnapshot> {
        val validPolicyId = policyId.requirePositiveNumber("policyId")
        require(limit in 1..MAX_HISTORY_LIMIT) {
            "limit must be between 1 and $MAX_HISTORY_LIMIT."
        }
        val query = QueryBuilder.byInstanceId(validPolicyId, ProductPolicy::class.java)
            .limit(limit)
            .build()
        return javers.findSnapshots(query)
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
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        val proposal = requireNotNull(findProposal(validProposalId)) {
            "Policy proposal $proposalId does not exist"
        }
        require(proposal.status == ProposalStatus.PENDING) {
            "Policy proposal $proposalId is already ${proposal.status}"
        }
        return proposal
    }

    private fun transitionPendingDecision(proposalId: Long, status: ProposalStatus, reviewer: String, reason: String) {
        val validProposalId = proposalId.requirePositiveNumber("proposalId")
        val updatedRows = transaction {
            PolicyProposalTable.update({
                (PolicyProposalTable.id eq validProposalId) and
                    (PolicyProposalTable.proposalStatus eq ProposalStatus.PENDING)
            }) {
                it[proposalStatus] = status
                it[PolicyProposalTable.reviewer] = reviewer
                it[PolicyProposalTable.reason] = reason
            }
        }
        require(updatedRows == 1) { "Policy proposal $proposalId is no longer pending" }
    }

    private fun validatePolicy(policy: ProductPolicy) {
        policy.id.requirePositiveNumber("policy.id")
        policy.title.requireNotBlank("policy.title")
        policy.pricing.currency.requireNotBlank("policy.pricing.currency")
        require(policy.pricing.currency.length == CURRENCY_CODE_LENGTH) {
            "policy.pricing.currency must be a 3-letter ISO currency code"
        }
        require(policy.pricing.amount.signum() >= 0) { "policy.pricing.amount must be zero or positive" }
        require(policy.pricing.amount.scale() <= MONEY_SCALE) {
            "policy.pricing.amount must use at most 2 decimal places"
        }
        require(policy.pricing.approvalLimit.signum() >= 0) {
            "policy.pricing.approvalLimit must be zero or positive"
        }
        require(policy.pricing.approvalLimit.scale() <= MONEY_SCALE) {
            "policy.pricing.approvalLimit must use at most 2 decimal places"
        }
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

    private fun ProductPolicy.toPolicyJson(): String =
        JaversCodecs.String.encode(javers.jsonConverter.toJsonElement(this) as JsonObject)
}

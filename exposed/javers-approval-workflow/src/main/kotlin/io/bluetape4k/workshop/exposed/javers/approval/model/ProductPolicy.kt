package io.bluetape4k.workshop.exposed.javers.approval.model

import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal

/**
 * Product policy aggregate reviewed before it becomes approved current state.
 *
 * ## Behavior / Contract
 * - [id] is the JaVers aggregate id.
 * - [pricing] is a nested value object so JaVers can show nested review diffs.
 * - Only approved proposals are committed as JaVers snapshots.
 */
@TypeName("ProductPolicy")
data class ProductPolicy(
    @Id val id: Long,
    val title: String,
    val status: PolicyStatus,
    val pricing: PricingPolicy,
    val owner: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Nested pricing policy value object.
 */
data class PricingPolicy(
    val currency: String,
    val amount: BigDecimal,
    val approvalLimit: BigDecimal,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Current product policy lifecycle state.
 */
enum class PolicyStatus {
    DRAFT,
    ACTIVE,
    RETIRED,
}

/**
 * Approval proposal lifecycle state.
 */
enum class ProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

/**
 * One reader-facing JaVers diff summary row.
 */
data class ChangedField(
    val path: String,
    val left: String?,
    val right: String?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Stored approval proposal and decision.
 */
data class PolicyProposal(
    val id: Long,
    val policyId: Long,
    val requester: String,
    val status: ProposalStatus,
    val proposed: ProductPolicy,
    val changedFields: List<ChangedField>,
    val reviewer: String? = null,
    val reason: String? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

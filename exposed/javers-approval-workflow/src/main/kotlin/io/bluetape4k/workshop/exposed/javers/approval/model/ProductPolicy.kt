package io.bluetape4k.workshop.exposed.javers.approval.model

import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal

/**
 * 승인된 현재 상태가 되기 전에 review되는 product policy aggregate이다.
 *
 * ## 동작 / 계약
 * - [id]는 JaVers aggregate id이다.
 * - [pricing]은 JaVers가 중첩 review diff를 보여 줄 수 있도록 둔 중첩 value object이다.
 * - 승인된 제안만 JaVers snapshot으로 commit된다.
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
 * 중첩 pricing policy value object이다.
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
 * 현재 product policy의 lifecycle 상태이다.
 */
enum class PolicyStatus {
    DRAFT,
    ACTIVE,
    RETIRED,
}

/**
 * 승인 제안의 lifecycle 상태이다.
 */
enum class ProposalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

/**
 * 독자에게 보여 줄 JaVers diff 요약 row 하나이다.
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
 * 저장된 승인 제안과 결정 정보이다.
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

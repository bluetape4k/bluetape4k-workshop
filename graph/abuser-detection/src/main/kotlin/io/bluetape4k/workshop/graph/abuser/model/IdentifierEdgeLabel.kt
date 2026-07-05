package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

/**
 * Domain-level dispatch enum for user-to-identifier edge types used in abuse cluster detection.
 *
 * This is NOT a schema declaration (see [io.bluetape4k.workshop.graph.abuser.schema]). It is a
 * typed wrapper over the raw edge-label strings so that service-layer dispatch is type-safe and
 * exhaustive.
 *
 * ## Behavior / Contract
 * - [all] is the authoritative ordered list of all identifier edge labels.
 * - [REFERRED_BY] is intentionally absent from [all] — referral alone does not indicate shared
 *   identity and must not participate in abuse-cluster BFS traversal.
 *
 * ## Usage
 * ```kotlin
 * IdentifierEdgeLabel.all.forEach { lbl ->
 *     val neighbors = ops.neighbors(userId, NeighborOptions(lbl.value, OUTGOING, 1))
 * }
 * ```
 */
@JvmInline
value class IdentifierEdgeLabel(val value: String): Serializable {

    init {
        value.requireNotBlank("value")
    }

    companion object {
        private const val serialVersionUID = 1L

        /** Links a User vertex to a Device vertex. */
        val USES_DEVICE = IdentifierEdgeLabel("USES_DEVICE")

        /** Links a User vertex to an IpAddress vertex. */
        val USES_IP = IdentifierEdgeLabel("USES_IP")

        /** Links a User vertex to a PhoneNumber vertex. */
        val HAS_PHONE = IdentifierEdgeLabel("HAS_PHONE")

        /** Links a User vertex to a PaymentMethod vertex. */
        val USES_PAYMENT = IdentifierEdgeLabel("USES_PAYMENT")

        /**
         * All identifier edge labels used for abuse cluster traversal.
         * REFERRED_BY is excluded — referral alone does not indicate shared identity.
         */
        val all: List<IdentifierEdgeLabel> = listOf(USES_DEVICE, USES_IP, HAS_PHONE, USES_PAYMENT)
    }
}

package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.model.GraphElementId
import java.io.Serializable

/**
 * A single identifier-sharing path between a user and one of their identifier vertices.
 *
 * ## Behavior / Contract
 * - [identifierVertexId] is the ID of the shared identifier vertex (Device, IpAddress, PhoneNumber,
 *   or PaymentMethod).
 * - [edgeLabel] identifies which type of relationship connects the user to the identifier.
 * - Callers can group multiple [AbusePath] results by [edgeLabel] to enumerate all identifier types
 *   that connect a suspicious user to shared resources.
 *
 * ## Usage
 * ```kotlin
 * val paths = service.explainSuspicion(userId)
 * val devicePaths = paths.filter { it.edgeLabel == IdentifierEdgeLabel.USES_DEVICE }
 * ```
 */
data class AbusePath(
    /** ID of the shared identifier vertex (Device, IpAddress, PhoneNumber, PaymentMethod). */
    val identifierVertexId: GraphElementId,
    /** Edge label type describing how the user is connected to this identifier. */
    val edgeLabel: IdentifierEdgeLabel,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

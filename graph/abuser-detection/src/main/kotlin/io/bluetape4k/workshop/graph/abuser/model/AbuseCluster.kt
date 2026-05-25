package io.bluetape4k.workshop.graph.abuser.model

import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * A cluster of user vertices that share at least one identifier (device, IP, phone, or payment).
 *
 * ## Behavior / Contract
 * - [seedUserId] is the starting user whose identifier graph was traversed.
 * - [users] contains all OTHER users reachable via shared identifier vertices — the seed user is
 *   excluded from this list.
 * - [sharedIdentifiers] are the identifier vertices (Device, IpAddress, PhoneNumber, PaymentMethod)
 *   that link the cluster together.
 * - An empty [users] list means the seed user has no shared-identifier neighbours and is not part
 *   of any abuse cluster.
 *
 * ## Usage
 * ```kotlin
 * val cluster = service.findAbuseCluster(userId)
 * if (cluster.users.isNotEmpty()) {
 *     log.warn { "User $userId is part of an abuse cluster with ${cluster.users.size} others" }
 * }
 * ```
 */
data class AbuseCluster(
    /** ID of the seed user whose neighbours were traversed. */
    val seedUserId: GraphElementId,
    /** Other users sharing at least one identifier with the seed user. */
    val users: List<GraphVertex>,
    /** Identifier vertices connecting the cluster. */
    val sharedIdentifiers: List<GraphVertex>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

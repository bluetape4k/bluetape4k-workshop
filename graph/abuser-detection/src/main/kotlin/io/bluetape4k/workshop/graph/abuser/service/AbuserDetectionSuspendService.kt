package io.bluetape4k.workshop.graph.abuser.service

import io.bluetape4k.graph.model.CycleOptions
import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphCycle
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PageRankOptions
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.graph.abuser.model.AbuseCluster
import io.bluetape4k.workshop.graph.abuser.model.AbusePath
import io.bluetape4k.workshop.graph.abuser.model.IdentifierEdgeLabel
import io.bluetape4k.workshop.graph.abuser.model.SuspiciousUserScore
import io.bluetape4k.workshop.graph.abuser.schema.DeviceLabel
import io.bluetape4k.workshop.graph.abuser.schema.HasPhoneLabel
import io.bluetape4k.workshop.graph.abuser.schema.IpAddressLabel
import io.bluetape4k.workshop.graph.abuser.schema.PaymentMethodLabel
import io.bluetape4k.workshop.graph.abuser.schema.PhoneNumberLabel
import io.bluetape4k.workshop.graph.abuser.schema.ReferredByLabel
import io.bluetape4k.workshop.graph.abuser.schema.UserLabel
import io.bluetape4k.workshop.graph.abuser.schema.UsesDeviceLabel
import io.bluetape4k.workshop.graph.abuser.schema.UsesIpLabel
import io.bluetape4k.workshop.graph.abuser.schema.UsesPaymentLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.withIndex

/**
 * Coroutine-based graph service for abuser detection.
 *
 * Mirrors the API of [AbuserDetectionService] with suspend/Flow return types for non-blocking
 * use in coroutine contexts. All read-heavy operations that can return many results expose a
 * cold [Flow] so callers control back-pressure and cancellation.
 *
 * ## Behavior / Contract
 * - [initialize] must be called before any other method to ensure the named graph exists.
 * - Vertex mutators are idempotent: they find an existing vertex by domain key or create one.
 * - Edge mutators call [GraphSuspendOperations.createEdge] directly; callers must avoid duplicate links.
 * - [findAbuseCluster] excludes the seed user from [AbuseCluster.users].
 * - [rankSuspiciousUsers] returns a cold [Flow] of User vertices ranked by PageRank; ranks are 1-based.
 * - Never wrap suspend calls in `runCatching` — [kotlinx.coroutines.CancellationException] must propagate.
 *
 * ## Usage
 * ```kotlin
 * val service = AbuserDetectionSuspendService(ops, "my_graph")
 * service.initialize()
 * val userV = service.addUser("u-1", "KR")
 * val devV  = service.addDevice("d-1", "android")
 * service.linkDevice(userV.id, devV.id, Instant.now().toString())
 * val cluster = service.findAbuseCluster(userV.id)
 * service.rankSuspiciousUsers(limit = 10).collect { score -> println(score) }
 * ```
 */
class AbuserDetectionSuspendService(
    private val ops: GraphSuspendOperations,
    private val graphName: String,
) {

    companion object : KLoggingChannel() {
        /**
         * Maps identifier vertex label → [IdentifierEdgeLabel] for reverse traversal.
         *
         * Used in [findAbuseCluster] to look up which INCOMING edge label to follow from an
         * identifier vertex back to its connected users.
         */
        private val VERTEX_LABEL_TO_EDGE_LABEL: Map<String, IdentifierEdgeLabel> = mapOf(
            DeviceLabel.label        to IdentifierEdgeLabel.USES_DEVICE,
            IpAddressLabel.label     to IdentifierEdgeLabel.USES_IP,
            PhoneNumberLabel.label   to IdentifierEdgeLabel.HAS_PHONE,
            PaymentMethodLabel.label to IdentifierEdgeLabel.USES_PAYMENT,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ensures the named graph exists. Safe to call multiple times — no-op when already created.
     */
    suspend fun initialize() {
        if (!ops.graphExists(graphName)) {
            log.debug { "Creating graph: $graphName" }
            ops.createGraph(graphName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vertex mutators (find-or-create by domain key)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds an existing User vertex by [userId] or creates a new one.
     *
     * @param userId stable user identifier (opaque string, e.g. UUID)
     * @param country ISO-3166-1 alpha-2 country code
     */
    suspend fun addUser(userId: String, country: String): GraphVertex {
        userId.requireNotBlank("userId")
        country.requireNotBlank("country")
        return ops.findVerticesByLabel(UserLabel.label, mapOf(UserLabel.userId.name to userId))
            .firstOrNull()
            ?: ops.createVertex(
                UserLabel.label,
                mapOf(UserLabel.userId.name to userId, UserLabel.country.name to country)
            )
    }

    /**
     * Finds an existing Device vertex by [deviceId] or creates a new one.
     *
     * @param deviceId unique device fingerprint
     * @param platform OS/platform string, e.g. `"android"`, `"ios"`, `"web"`
     */
    suspend fun addDevice(deviceId: String, platform: String): GraphVertex {
        deviceId.requireNotBlank("deviceId")
        platform.requireNotBlank("platform")
        return ops.findVerticesByLabel(DeviceLabel.label, mapOf(DeviceLabel.deviceId.name to deviceId))
            .firstOrNull()
            ?: ops.createVertex(
                DeviceLabel.label,
                mapOf(DeviceLabel.deviceId.name to deviceId, DeviceLabel.platform.name to platform)
            )
    }

    /**
     * Finds an existing IpAddress vertex by [ip] or creates a new one.
     *
     * @param ip IPv4 or IPv6 address string
     */
    suspend fun addIpAddress(ip: String): GraphVertex {
        ip.requireNotBlank("ip")
        return ops.findVerticesByLabel(IpAddressLabel.label, mapOf(IpAddressLabel.ip.name to ip))
            .firstOrNull()
            ?: ops.createVertex(IpAddressLabel.label, mapOf(IpAddressLabel.ip.name to ip))
    }

    /**
     * Finds an existing PhoneNumber vertex by hashed phone or creates a new one.
     *
     * **Security**: [hashedPhone] MUST be an E.164-format SHA-256 hex hash.
     * Raw phone numbers MUST NOT be passed to this method.
     *
     * @param hashedPhone E.164 SHA-256 hex of the phone number
     */
    suspend fun addPhoneNumber(hashedPhone: String): GraphVertex {
        hashedPhone.requireNotBlank("hashedPhone")
        return ops.findVerticesByLabel(PhoneNumberLabel.label, mapOf(PhoneNumberLabel.phone.name to hashedPhone))
            .firstOrNull()
            ?: ops.createVertex(PhoneNumberLabel.label, mapOf(PhoneNumberLabel.phone.name to hashedPhone))
    }

    /**
     * Finds an existing PaymentMethod vertex by [paymentToken] or creates a new one.
     *
     * **Security**: [paymentToken] MUST be a PCI-safe processor token.
     * Raw PAN or CVV MUST NOT be passed to this method.
     *
     * @param paymentToken processor-issued payment token (never a raw PAN or CVV)
     */
    suspend fun addPaymentMethod(paymentToken: String): GraphVertex {
        paymentToken.requireNotBlank("paymentToken")
        return ops.findVerticesByLabel(
            PaymentMethodLabel.label,
            mapOf(PaymentMethodLabel.paymentToken.name to paymentToken)
        )
            .firstOrNull()
            ?: ops.createVertex(
                PaymentMethodLabel.label,
                mapOf(PaymentMethodLabel.paymentToken.name to paymentToken)
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge mutators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Links a user to a device with a `USES_DEVICE` edge.
     *
     * @param userVertexId graph ID of the User vertex
     * @param deviceVertexId graph ID of the Device vertex
     * @param occurredAt ISO-8601 timestamp of the first login from this device
     */
    suspend fun linkDevice(
        userVertexId: GraphElementId,
        deviceVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        return ops.createEdge(
            userVertexId,
            deviceVertexId,
            UsesDeviceLabel.label,
            mapOf(UsesDeviceLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * Links a user to an IP address with a `USES_IP` edge.
     *
     * @param userVertexId graph ID of the User vertex
     * @param ipVertexId graph ID of the IpAddress vertex
     * @param occurredAt ISO-8601 timestamp of the first observed connection
     */
    suspend fun linkIp(
        userVertexId: GraphElementId,
        ipVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        return ops.createEdge(
            userVertexId,
            ipVertexId,
            UsesIpLabel.label,
            mapOf(UsesIpLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * Links a user to a phone number with a `HAS_PHONE` edge.
     *
     * @param userVertexId graph ID of the User vertex
     * @param phoneVertexId graph ID of the PhoneNumber vertex
     * @param occurredAt ISO-8601 timestamp of the first association
     */
    suspend fun linkPhone(
        userVertexId: GraphElementId,
        phoneVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        return ops.createEdge(
            userVertexId,
            phoneVertexId,
            HasPhoneLabel.label,
            mapOf(HasPhoneLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * Links a user to a payment method with a `USES_PAYMENT` edge.
     *
     * @param userVertexId graph ID of the User vertex
     * @param paymentVertexId graph ID of the PaymentMethod vertex
     * @param occurredAt ISO-8601 timestamp of the first payment attempt
     */
    suspend fun linkPayment(
        userVertexId: GraphElementId,
        paymentVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        return ops.createEdge(
            userVertexId,
            paymentVertexId,
            UsesPaymentLabel.label,
            mapOf(UsesPaymentLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * Records a referral relationship from [referrerVertexId] to [referredVertexId].
     *
     * @param referrerVertexId graph ID of the referring User vertex
     * @param referredVertexId graph ID of the referred User vertex
     * @param occurredAt ISO-8601 timestamp of the referral event
     */
    suspend fun linkReferral(
        referrerVertexId: GraphElementId,
        referredVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        return ops.createEdge(
            referrerVertexId,
            referredVertexId,
            ReferredByLabel.label,
            mapOf(ReferredByLabel.occurredAt.name to occurredAt),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Query algorithms
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Finds all users that share at least one identifier with the given seed user.
     *
     * Algorithm:
     * 1. Collect all identifier vertices reachable from [seedUserId] via OUTGOING identifier edges.
     * 2. For each identifier vertex, collect INCOMING users via the same identifier edge type.
     * 3. Remove the seed user itself from the result.
     *
     * @param seedUserId graph vertex ID of the seed user
     * @return [AbuseCluster] where [AbuseCluster.users] excludes the seed user.
     *         Returns an empty cluster if the seed user does not exist.
     */
    suspend fun findAbuseCluster(seedUserId: GraphElementId): AbuseCluster {
        val emptyCluster = AbuseCluster(seedUserId, emptyList(), emptyList())

        if (ops.findVertexById(seedUserId) == null) {
            log.warn { "findAbuseCluster: seed user vertex not found [seedUserId=$seedUserId, graphName=$graphName] — returning empty cluster" }
            return emptyCluster
        }

        // Step 1: traverse OUTGOING identifier edges to gather shared identifiers
        val seedIdentifiers = buildList {
            for (edgeLabel in IdentifierEdgeLabel.all) {
                addAll(
                    ops.neighbors(seedUserId, NeighborOptions(edgeLabel.value, Direction.OUTGOING, 1))
                        .toList()
                )
            }
        }

        if (seedIdentifiers.isEmpty()) {
            return emptyCluster
        }

        // Step 2: reverse-traverse from each identifier to find co-connected users
        val clusterUsers = mutableListOf<GraphVertex>()
        for (identifierVertex in seedIdentifiers) {
            val reverseLabel = VERTEX_LABEL_TO_EDGE_LABEL[identifierVertex.label]
            if (reverseLabel == null) {
                log.warn { "findAbuseCluster: unknown identifier vertex label '${identifierVertex.label}' — skipping reverse traversal" }
                continue
            }
            ops.neighbors(
                identifierVertex.id,
                NeighborOptions(reverseLabel.value, Direction.INCOMING, 1)
            ).toList().filterTo(clusterUsers) { it.id != seedUserId }
        }

        return AbuseCluster(
            seedUserId = seedUserId,
            users = clusterUsers.distinctBy { it.id },
            sharedIdentifiers = seedIdentifiers.distinctBy { it.id },
        )
    }

    /**
     * Returns a cold [Flow] of shared identifier paths that connect the given user to other users.
     *
     * Each [AbusePath] describes one identifier vertex and the edge type connecting the user to it.
     *
     * @param userId graph vertex ID of the user to explain
     * @return cold [Flow] of [AbusePath] entries; empty if the user has no outgoing identifier edges
     */
    fun explainSuspicion(userId: GraphElementId): Flow<AbusePath> = flow {
        for (edgeLabel in IdentifierEdgeLabel.all) {
            ops.findEdgesByStartId(userId, edgeLabel.value).collect { edge ->
                emit(AbusePath(identifierVertexId = edge.endId, edgeLabel = edgeLabel))
            }
        }
    }

    /**
     * Returns a cold [Flow] of detected referral loops — cycles among User vertices via REFERRED_BY edges.
     *
     * @param maxDepth maximum traversal depth when searching for cycles (default 6)
     * @param maxCycles maximum number of cycles to return (default 100)
     * @return cold [Flow] of [GraphCycle]s; empty if no loops exist
     */
    fun detectReferralLoops(maxDepth: Int = 6, maxCycles: Int = 100): Flow<GraphCycle> {
        maxDepth.requirePositiveNumber("maxDepth")
        maxCycles.requirePositiveNumber("maxCycles")
        return ops.detectCycles(
            CycleOptions(
                vertexLabel = UserLabel.label,
                edgeLabel   = ReferredByLabel.label,
                maxDepth    = maxDepth,
                maxCycles   = maxCycles,
            )
        )
    }

    /**
     * Returns a cold [Flow] of users ranked by PageRank score descending.
     *
     * PageRank is computed over all graph vertices and edges; results are then filtered to the
     * User label. Higher score indicates a user is more highly connected in the identity graph.
     * Each emitted [SuspiciousUserScore.rank] is 1-based.
     *
     * @param limit maximum number of [SuspiciousUserScore] entries to emit (default 20, must be > 0)
     * @return cold [Flow] sorted descending by score with 1-based [SuspiciousUserScore.rank]
     */
    fun rankSuspiciousUsers(limit: Int = 20): Flow<SuspiciousUserScore> {
        limit.requirePositiveNumber("limit")
        // vertexLabel = UserLabel.label ensures topK = limit applies directly to users.
        return ops.pageRank(PageRankOptions(vertexLabel = UserLabel.label, topK = limit))
            .withIndex()
            .map { (index, pageRankScore) ->
                SuspiciousUserScore(
                    user  = pageRankScore.vertex,
                    score = pageRankScore.score,
                    rank  = index + 1,
                )
            }
    }
}

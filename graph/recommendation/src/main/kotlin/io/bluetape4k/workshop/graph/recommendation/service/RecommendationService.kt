package io.bluetape4k.workshop.graph.recommendation.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.graph.recommendation.DEFAULT_RECOMMENDATION_LIMIT
import io.bluetape4k.workshop.graph.recommendation.MAX_RECOMMENDATION_LIMIT
import io.bluetape4k.workshop.graph.recommendation.model.CandidateExclusion
import io.bluetape4k.workshop.graph.recommendation.model.ExplainedFollowRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.ExplainedProductRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.FollowEvidencePath
import io.bluetape4k.workshop.graph.recommendation.model.FollowRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.ProductEvidencePath
import io.bluetape4k.workshop.graph.recommendation.model.ProductRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.RecommendationExclusionReason.ALREADY_FOLLOWED
import io.bluetape4k.workshop.graph.recommendation.model.RecommendationExclusionReason.ALREADY_PURCHASED
import io.bluetape4k.workshop.graph.recommendation.model.RecommendationExclusionReason.SELF
import io.bluetape4k.workshop.graph.recommendation.schema.FollowsLabel
import io.bluetape4k.workshop.graph.recommendation.schema.ProductLabel
import io.bluetape4k.workshop.graph.recommendation.schema.PurchasedLabel
import io.bluetape4k.workshop.graph.recommendation.schema.UserLabel

/**
 * Blocking graph service for e-commerce product and social follow recommendations.
 *
 * Uses a named graph on the given [GraphOperations] backend.
 *
 * ## Behavior / Contract
 * - [initialize] must be called once before any other method to ensure the named graph exists.
 * - Vertex mutators ([addUser], [addProduct]) are idempotent: find-by-domain-key or create.
 * - [purchase] and [follow] are NOT idempotent — repeated calls create additional edges.
 *   Deduplication is handled at the algorithm level (co-buyer ID set).
 * - [recommendProducts] returns `emptyList()` when [userVertexId] is not found or has no purchases.
 * - [recommendFollows] returns `emptyList()` when [userVertexId] is not found or has no follows.
 * - [follow] throws [IllegalArgumentException] when follower equals followee.
 *
 * ## Known Limitations (workshop demo scope)
 * - **N+1 traversal**: [recommendProducts] issues one neighbor query per seed product and one per
 *   co-buyer; [recommendFollows] issues one outgoing neighbor query per direct follow. The `limit`
 *   parameter bounds output count, not I/O calls. For large graphs, replace with native
 *   Cypher/Gremlin queries.
 * - **TOCTOU in [initialize]**: The `graphExists → createGraph` check is not atomic and assumes
 *   a single-instance deployment. Concurrent callers may attempt duplicate creation — acceptable
 *   for this demo; production code should use advisory locking or server-side upsert semantics.
 * - **No vertex-type enforcement at runtime**: [purchase] and [follow] accept any
 *   [io.bluetape4k.graph.model.GraphElementId] without verifying that it belongs to a User or
 *   Product vertex. Callers are responsible for passing IDs returned by [addUser] / [addProduct].
 *
 * ## Usage
 * ```kotlin
 * val service = RecommendationService(ops, "recommendation")
 * service.initialize()
 * val alice = service.addUser("alice", "Alice")
 * val laptop = service.addProduct("laptop", "Laptop", category = "Electronics")
 * service.purchase(alice.id, laptop.id, rating = 5)
 * val recs = service.recommendProducts(alice.id)
 * ```
 */
class RecommendationService(
    private val ops: GraphOperations,
    private val graphName: String,
) {
    companion object : KLogging()

    /**
     * Ensures the named graph exists. Safe to call multiple times — no-op when already created.
     */
    fun initialize() {
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
     * @param userId stable domain key (opaque string, e.g. username or UUID)
     * @param name display name
     */
    fun addUser(userId: String, name: String): GraphVertex {
        userId.requireNotBlank("userId")
        name.requireNotBlank("name")
        return ops.findVerticesByLabel(UserLabel.label, mapOf(UserLabel.userId.name to userId))
            .firstOrNull()
            ?: ops.createVertex(
                UserLabel.label,
                mapOf(
                    UserLabel.userId.name to userId,
                    UserLabel.name.name to name,
                )
            )
    }

    /**
     * Finds an existing Product vertex by [productId] or creates a new one.
     *
     * @param productId stable domain key (opaque string, e.g. SKU or UUID)
     * @param name display name
     * @param category product category (optional)
     */
    fun addProduct(productId: String, name: String, category: String = ""): GraphVertex {
        productId.requireNotBlank("productId")
        name.requireNotBlank("name")
        return ops.findVerticesByLabel(ProductLabel.label, mapOf(ProductLabel.productId.name to productId))
            .firstOrNull()
            ?: ops.createVertex(
                ProductLabel.label,
                mapOf(
                    ProductLabel.productId.name to productId,
                    ProductLabel.name.name to name,
                    ProductLabel.category.name to category,
                )
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge mutators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Creates a PURCHASED edge from a User vertex to a Product vertex.
     *
     * Not idempotent — repeated calls create additional edges.
     *
     * @param userVertexId graph ID of the User vertex
     * @param productVertexId graph ID of the Product vertex
     * @param rating purchase rating 0–5; 0 means unrated (stored only when > 0)
     * @param purchasedAt ISO-8601 timestamp (optional)
     */
    fun purchase(
        userVertexId: GraphElementId,
        productVertexId: GraphElementId,
        rating: Int = 0,
        purchasedAt: String = "",
    ): GraphEdge {
        rating.requireInRange(0, 5, "rating")
        val props = buildMap {
            if (rating > 0) put(PurchasedLabel.rating.name, rating.toString())
            if (purchasedAt.isNotBlank()) put(PurchasedLabel.purchasedAt.name, purchasedAt)
        }
        return ops.createEdge(userVertexId, productVertexId, PurchasedLabel.label, props)
    }

    /**
     * Creates a unidirectional FOLLOWS edge from [followerVertexId] to [followeeVertexId].
     *
     * Not idempotent — repeated calls create additional edges.
     *
     * @param followerVertexId graph ID of the User who follows
     * @param followeeVertexId graph ID of the User being followed
     * @throws IllegalArgumentException when [followerVertexId] equals [followeeVertexId]
     */
    fun follow(followerVertexId: GraphElementId, followeeVertexId: GraphElementId): GraphEdge {
        require(followerVertexId != followeeVertexId) {
            "followerVertexId must differ from followeeVertexId"
        }
        return ops.createEdge(followerVertexId, followeeVertexId, FollowsLabel.label, emptyMap())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recommendation queries
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns products bought by co-buyers of the seed user's products,
     * ranked by distinct co-buyer count (score).
     *
     * Already-purchased products are excluded from results.
     *
     * **N+1 warning**: issues one neighbor query per product and one per co-buyer.
     * For large graphs, prefer native Cypher/Gremlin queries.
     *
     * ## Algorithm
     * 1. Collect the seed user's purchased products (OUTGOING PURCHASED edges).
     * 2. For each product, collect co-buyers (INCOMING PURCHASED from others).
     * 3. For each co-buyer, collect their other products (excluding seed's products).
     * 4. Accumulate co-buyer ID sets per candidate product; score = distinct co-buyer count.
     * 5. Sort by score descending, then by `productId` ascending for tie-breaking.
     *
     * @param userVertexId GraphVertex.id returned by [addUser]
     * @param limit 1..[MAX_RECOMMENDATION_LIMIT]
     * @return ranked list of [ProductRecommendation]; `emptyList()` when user not found or has no purchases
     */
    fun recommendProducts(userVertexId: GraphElementId, limit: Int = DEFAULT_RECOMMENDATION_LIMIT): List<ProductRecommendation> {
        return explainProductRecommendations(userVertexId, limit).map { it.recommendation }
    }

    /**
     * Returns product recommendations with the concrete co-buyer paths and exclusion rules
     * that explain each score.
     *
     * [ProductRecommendation.score] still equals the number of distinct co-buyers. The
     * [ExplainedProductRecommendation.evidencePaths] list keeps the human-readable path
     * details: seed product → co-buyer → candidate product.
     */
    fun explainProductRecommendations(
        userVertexId: GraphElementId,
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT,
    ): List<ExplainedProductRecommendation> {
        limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

        val myProducts = ops.neighbors(userVertexId, NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1))
        if (myProducts.isEmpty()) {
            log.debug { "recommendProducts: userVertexId=$userVertexId has no PURCHASED products (user may not exist or has no purchases)" }
            return emptyList()
        }
        val myProductIds = myProducts.map { it.id }.toSet()
        val excludedCandidates = myProducts.map { CandidateExclusion(it.id, ALREADY_PURCHASED) }

        // candidateMap: candidateProductId → (candidateVertex, evidence paths)
        val candidateMap = mutableMapOf<GraphElementId, Pair<GraphVertex, MutableList<ProductEvidencePath>>>()

        for (product in myProducts) {
            val coBuyers = ops.neighbors(product.id, NeighborOptions(PurchasedLabel.label, Direction.INCOMING, 1))
                .filter { it.id != userVertexId }

            for (coBuyer in coBuyers) {
                val theirProducts = ops.neighbors(
                    coBuyer.id,
                    NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1)
                ).filter { it.id !in myProductIds }

                for (candidate in theirProducts) {
                    candidateMap.getOrPut(candidate.id) { candidate to mutableListOf() }
                        .second += ProductEvidencePath(product, coBuyer, candidate)
                }
            }
        }

        return candidateMap.values
            .map { (product, evidencePaths) ->
                val distinctPaths = evidencePaths.distinctBy {
                    Triple(it.sharedProduct.id, it.coBuyer.id, it.candidateProduct.id)
                }
                val sharedBuyers = distinctPaths.distinctBy { it.coBuyer.id }.map { it.coBuyer }
                ExplainedProductRecommendation(
                    recommendation = ProductRecommendation(product, sharedBuyers.size, sharedBuyers),
                    evidencePaths = distinctPaths,
                    excludedCandidates = excludedCandidates,
                )
            }
            .sortedWith(
                compareByDescending<ExplainedProductRecommendation> { it.recommendation.score }
                    .thenBy { it.recommendation.product.properties[ProductLabel.productId.name]?.toString() ?: "" }
            )
            .take(limit)
    }

    /**
     * Recommends users to follow based on 2-hop FOLLOWS traversal (FOAF).
     *
     * Already-followed users and the seed user itself are excluded from results.
     *
     * **N+1 warning**: issues one OUTGOING neighbor query per direct follow.
     *
     * ## Algorithm
     * 1. Collect seed's direct follows (OUTGOING FOLLOWS depth-1).
     * 2. For each direct follow, collect their outgoing follows as candidate paths.
     * 3. Exclude the seed user and already-followed users; record the exclusion reason.
     * 4. Sort by mutual-follow count descending, then by `userId` ascending for tie-breaking.
     *
     * @param userVertexId GraphVertex.id returned by [addUser]
     * @param limit 1..[MAX_RECOMMENDATION_LIMIT]
     * @return ranked list of [FollowRecommendation]; `emptyList()` when user not found or has no follows
     */
    fun recommendFollows(userVertexId: GraphElementId, limit: Int = DEFAULT_RECOMMENDATION_LIMIT): List<FollowRecommendation> {
        return explainFollowRecommendations(userVertexId, limit).map { it.recommendation }
    }

    /**
     * Returns follow recommendations with FOAF evidence and excluded candidate rules.
     *
     * The evidence path is `seed user -> intermediary -> candidate`. Already-followed
     * users and the seed user itself are recorded in [ExplainedFollowRecommendation.excludedCandidates].
     */
    fun explainFollowRecommendations(
        userVertexId: GraphElementId,
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT,
    ): List<ExplainedFollowRecommendation> {
        limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

        val myFollows = ops.neighbors(userVertexId, NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 1))
        if (myFollows.isEmpty()) {
            log.debug { "recommendFollows: userVertexId=$userVertexId has no FOLLOWS edges (user may not exist or follows nobody)" }
            return emptyList()
        }
        val myFollowIds = myFollows.map { it.id }.toSet()
        val excludedCandidates = myFollows.map { CandidateExclusion(it.id, ALREADY_FOLLOWED) }.toMutableList()
        val candidateMap = mutableMapOf<GraphElementId, Pair<GraphVertex, MutableList<FollowEvidencePath>>>()

        for (intermediary in myFollows) {
            val candidates = ops.neighbors(
                intermediary.id,
                NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 1)
            )

            for (candidate in candidates) {
                when {
                    candidate.id == userVertexId ->
                        excludedCandidates += CandidateExclusion(candidate.id, SELF, intermediary)

                    candidate.id in myFollowIds ->
                        excludedCandidates += CandidateExclusion(candidate.id, ALREADY_FOLLOWED, intermediary)

                    else ->
                        candidateMap.getOrPut(candidate.id) { candidate to mutableListOf() }
                            .second += FollowEvidencePath(intermediary, candidate)
                }
            }
        }

        val distinctExclusions = excludedCandidates.distinctBy { Triple(it.candidateId, it.reason, it.via?.id) }

        return candidateMap.values
            .map { (candidate, evidencePaths) ->
                val distinctPaths = evidencePaths.distinctBy { it.intermediary.id to it.candidate.id }
                val intermediaries = distinctPaths.distinctBy { it.intermediary.id }.map { it.intermediary }
                ExplainedFollowRecommendation(
                    recommendation = FollowRecommendation(candidate, intermediaries.size, intermediaries),
                    evidencePaths = distinctPaths,
                    excludedCandidates = distinctExclusions,
                )
            }
            .filter { it.recommendation.mutualFollowCount > 0 }
            .sortedWith(
                compareByDescending<ExplainedFollowRecommendation> { it.recommendation.mutualFollowCount }
                    .thenBy { it.recommendation.person.properties[UserLabel.userId.name]?.toString() ?: "" }
            )
            .take(limit)
    }
}

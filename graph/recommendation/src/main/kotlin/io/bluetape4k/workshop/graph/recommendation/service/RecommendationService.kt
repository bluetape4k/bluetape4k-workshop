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
import io.bluetape4k.workshop.graph.recommendation.MAX_RECOMMENDATION_LIMIT
import io.bluetape4k.workshop.graph.recommendation.model.FollowRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.ProductRecommendation
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
                buildMap {
                    put(ProductLabel.productId.name, productId)
                    put(ProductLabel.name.name, name)
                    put(ProductLabel.category.name, category)
                }
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
    fun recommendProducts(userVertexId: GraphElementId, limit: Int = 10): List<ProductRecommendation> {
        limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

        val myProducts = ops.neighbors(userVertexId, NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1))
        if (myProducts.isEmpty()) return emptyList()
        val myProductIds = myProducts.map { it.id }.toSet()

        // candidateMap: candidateProductId → (candidateVertex, Set<coBuyerId>)
        val candidateMap = mutableMapOf<GraphElementId, Pair<GraphVertex, MutableSet<GraphElementId>>>()

        for (product in myProducts) {
            val coBuyers = ops.neighbors(product.id, NeighborOptions(PurchasedLabel.label, Direction.INCOMING, 1))
                .filter { it.id != userVertexId }

            for (coBuyer in coBuyers) {
                val theirProducts = ops.neighbors(
                    coBuyer.id,
                    NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1)
                ).filter { it.id !in myProductIds }

                for (candidate in theirProducts) {
                    candidateMap.getOrPut(candidate.id) { candidate to mutableSetOf() }
                        .second += coBuyer.id
                }
            }
        }

        return candidateMap.values
            .map { (product, buyerIds) ->
                ProductRecommendation(product, buyerIds.size, emptyList())
            }
            .sortedWith(
                compareByDescending<ProductRecommendation> { it.score }
                    .thenBy { it.product.properties[ProductLabel.productId.name]?.toString() ?: "" }
            )
            .take(limit)
    }

    /**
     * Recommends users to follow based on 2-hop FOLLOWS traversal (FOAF).
     *
     * Already-followed users and the seed user itself are excluded from results.
     *
     * **N+1 warning**: issues one INCOMING neighbor query per depth-2 candidate.
     *
     * ## Algorithm
     * 1. Collect seed's direct follows (OUTGOING FOLLOWS depth-1).
     * 2. Collect depth-2 candidates (OUTGOING FOLLOWS depth-2); exclude seed and already-followed.
     * 3. For each candidate, count how many of seed's follows also follow that candidate
     *    (INCOMING FOLLOWS on candidate, intersected with seed's follow IDs).
     * 4. Sort by mutual-follow count descending, then by `userId` ascending for tie-breaking.
     *
     * @param userVertexId GraphVertex.id returned by [addUser]
     * @param limit 1..[MAX_RECOMMENDATION_LIMIT]
     * @return ranked list of [FollowRecommendation]; `emptyList()` when user not found or has no follows
     */
    fun recommendFollows(userVertexId: GraphElementId, limit: Int = 10): List<FollowRecommendation> {
        limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

        val myFollows = ops.neighbors(userVertexId, NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 1))
        if (myFollows.isEmpty()) return emptyList()
        val myFollowIds = myFollows.map { it.id }.toSet()

        val candidates = ops.neighbors(userVertexId, NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 2))
            .filter { it.id != userVertexId && it.id !in myFollowIds }
            .distinctBy { it.id }

        return candidates
            .map { candidate ->
                val whoFollowsCandidate = ops.neighbors(
                    candidate.id,
                    NeighborOptions(FollowsLabel.label, Direction.INCOMING, 1)
                )
                val intermediaries = whoFollowsCandidate.filter { it.id in myFollowIds }
                FollowRecommendation(candidate, intermediaries.size, intermediaries)
            }
            .filter { it.mutualFollowCount > 0 }
            .sortedWith(
                compareByDescending<FollowRecommendation> { it.mutualFollowCount }
                    .thenBy { it.person.properties[UserLabel.userId.name]?.toString() ?: "" }
            )
            .take(limit)
    }
}

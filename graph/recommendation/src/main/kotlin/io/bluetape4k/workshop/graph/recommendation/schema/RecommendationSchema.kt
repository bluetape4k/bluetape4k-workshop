package io.bluetape4k.workshop.graph.recommendation.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// Vertex Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Vertex label for a user node in the recommendation graph.
 *
 * ## Properties
 * - `userId` — stable domain key (opaque string, e.g. username or UUID)
 * - `name` — display name
 */
object UserLabel : VertexLabel("User") {
    val userId = string("userId")
    val name = string("name")
}

/**
 * Vertex label for a product node in the recommendation graph.
 *
 * ## Properties
 * - `productId` — stable domain key (opaque string, e.g. SKU or UUID)
 * - `name` — display name
 * - `category` — product category (optional)
 */
object ProductLabel : VertexLabel("Product") {
    val productId = string("productId")
    val name = string("name")
    val category = string("category")
}

// ────────────────────────────────────────────────────────────────────────────
// Edge Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Purchase edge from a User vertex to a Product vertex.
 *
 * Multiple PURCHASED edges from the same user to the same product are allowed
 * (no idempotency guarantee — each [purchase][io.bluetape4k.workshop.graph.recommendation.service.RecommendationService.purchase]
 * call creates a new edge). Deduplication is handled at the recommendation algorithm level.
 *
 * ## Properties
 * - `rating` — purchase rating "1"–"5" stored as String; absent when not rated
 * - `purchasedAt` — ISO-8601 timestamp; absent when not set
 */
object PurchasedLabel : EdgeLabel("PURCHASED", UserLabel, ProductLabel) {
    val rating = string("rating")
    val purchasedAt = string("purchasedAt")
}

/**
 * Unidirectional follow edge from one User vertex to another.
 *
 * Unlike a bidirectional connection, FOLLOWS is one-directional —
 * only one edge is created per follower/followee pair.
 */
object FollowsLabel : EdgeLabel("FOLLOWS", UserLabel, UserLabel)

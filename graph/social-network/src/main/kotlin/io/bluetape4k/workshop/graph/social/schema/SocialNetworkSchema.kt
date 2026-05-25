package io.bluetape4k.workshop.graph.social.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// Vertex Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Vertex label for a person node in the social network.
 *
 * ## Properties
 * - `personId` — stable domain key (opaque string, e.g. username or UUID)
 * - `name` — display name
 * - `title` — professional title (optional)
 * - `location` — city or region (optional)
 */
object PersonLabel : VertexLabel("Person") {
    val personId = string("personId")
    val name = string("name")
    val title = string("title")
    val location = string("location")
}

/**
 * Vertex label for a company node in the social network.
 *
 * ## Properties
 * - `companyId` — stable domain key (opaque string)
 * - `name` — company display name
 * - `industry` — industry sector (optional)
 * - `location` — headquarters city or region (optional)
 */
object CompanyLabel : VertexLabel("Company") {
    val companyId = string("companyId")
    val name = string("name")
    val industry = string("industry")
    val location = string("location")
}

// ────────────────────────────────────────────────────────────────────────────
// Edge Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Bidirectional acquaintance edge between two Person vertices.
 *
 * **Important**: Stored as TWO directed edges (A→B and B→A) with identical properties.
 * Call [io.bluetape4k.workshop.graph.social.service.SocialNetworkService.connect] once per pair —
 * never call it again with arguments reversed.
 *
 * ## Properties
 * - `since` — ISO-8601 date when the connection was established (optional)
 * - `strength` — connection strength 1–10, stored as String (e.g. "8")
 */
object KnowsLabel : EdgeLabel("KNOWS", PersonLabel, PersonLabel) {
    val since = string("since")
    val strength = string("strength")   // stored as String, valid values "1".."10"
}

/**
 * Employment edge from a Person to a Company.
 *
 * ## Properties
 * - `role` — job title or role name (required, must not be blank)
 * - `startDate` — ISO-8601 employment start date (optional)
 * - `isCurrent` — "true" or "false" indicating active employment (stored as String)
 */
object WorksAtLabel : EdgeLabel("WORKS_AT", PersonLabel, CompanyLabel) {
    val role = string("role")
    val startDate = string("startDate")
    val isCurrent = string("isCurrent")  // stored as String "true"/"false"
}

/**
 * Unidirectional follower edge from one Person to another.
 *
 * Unlike [KnowsLabel], FOLLOWS is one-directional — only one edge is created.
 * A FOLLOWS relationship does not imply mutual acquaintance.
 */
object FollowsLabel : EdgeLabel("FOLLOWS", PersonLabel, PersonLabel)

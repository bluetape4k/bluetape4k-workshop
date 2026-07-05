package io.bluetape4k.workshop.graph.social.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphPath
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PathOptions
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.graph.social.model.ConnectionRecommendation
import io.bluetape4k.workshop.graph.social.schema.CompanyLabel
import io.bluetape4k.workshop.graph.social.schema.FollowsLabel
import io.bluetape4k.workshop.graph.social.schema.KnowsLabel
import io.bluetape4k.workshop.graph.social.schema.PersonLabel
import io.bluetape4k.workshop.graph.social.schema.WorksAtLabel

/**
 * Blocking graph service for LinkedIn-style social network operations.
 *
 * Uses a named graph on the given [GraphOperations] backend to manage person/company nodes,
 * bidirectional connections, follows, work experience, and network traversal algorithms.
 *
 * ## Behavior / Contract
 * - [initialize] must be called once before any other method to ensure the named graph exists.
 * - Vertex mutators ([addPerson], [addCompany]) are idempotent: find-by-domain-key or create.
 * - [connect] stores BIDIRECTIONAL connection as TWO directed edges (A→B and B→A).
 *   Call it once per pair — never call again with arguments reversed.
 * - [follow] is unidirectional: one edge from follower to followee.
 * - Edge mutators reject missing vertices and source/target label mismatches with [IllegalArgumentException].
 * - [recommendConnections] has O(N) per-candidate neighbor lookups — use with care on large graphs.
 * - [getNthDegreeConnections] excludes the seed vertex from both the full set and the closer set.
 *
 * ## Usage
 * ```kotlin
 * val service = SocialNetworkService(ops, "social_graph")
 * service.initialize()
 * val alice = service.addPerson("alice", "Alice Smith", title = "Engineer")
 * val bob   = service.addPerson("bob", "Bob Jones", title = "Designer")
 * service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)
 * val recs  = service.recommendConnections(alice.id)
 * ```
 */
class SocialNetworkService(
    private val ops: GraphOperations,
    private val graphName: String,
) {
    init {
        graphName.requireNotBlank("graphName")
    }

    companion object : KLogging() {
        /**
         * Maximum allowed traversal depth for network queries.
         * LinkedIn-equivalent "6 degrees of separation" upper bound.
         */
        const val MAX_TRAVERSAL_DEPTH: Int = 6
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

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
     * Finds an existing Person vertex by [personId] or creates a new one.
     *
     * @param personId stable domain key (opaque string, e.g. username or UUID)
     * @param name display name
     * @param title professional title (optional)
     * @param location city or region (optional)
     */
    fun addPerson(
        personId: String,
        name: String,
        title: String = "",
        location: String = "",
    ): GraphVertex {
        personId.requireNotBlank("personId")
        name.requireNotBlank("name")
        return ops.findVerticesByLabel(PersonLabel.label, mapOf(PersonLabel.personId.name to personId))
            .firstOrNull()
            ?: ops.createVertex(
                PersonLabel.label,
                buildMap {
                    put(PersonLabel.personId.name, personId)
                    put(PersonLabel.name.name, name)
                    if (title.isNotBlank()) put(PersonLabel.title.name, title)
                    if (location.isNotBlank()) put(PersonLabel.location.name, location)
                }
            )
    }

    /**
     * Finds an existing Company vertex by [companyId] or creates a new one.
     *
     * @param companyId stable domain key (opaque string)
     * @param name company display name
     * @param industry industry sector (optional)
     * @param location headquarters city or region (optional)
     */
    fun addCompany(
        companyId: String,
        name: String,
        industry: String = "",
        location: String = "",
    ): GraphVertex {
        companyId.requireNotBlank("companyId")
        name.requireNotBlank("name")
        return ops.findVerticesByLabel(CompanyLabel.label, mapOf(CompanyLabel.companyId.name to companyId))
            .firstOrNull()
            ?: ops.createVertex(
                CompanyLabel.label,
                buildMap {
                    put(CompanyLabel.companyId.name, companyId)
                    put(CompanyLabel.name.name, name)
                    if (industry.isNotBlank()) put(CompanyLabel.industry.name, industry)
                    if (location.isNotBlank()) put(CompanyLabel.location.name, location)
                }
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Edge mutators
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Establishes a bidirectional KNOWS connection between two Person vertices.
     *
     * Stores TWO directed edges (from→to and to→from) with identical properties.
     * Call this method once per pair — **never call again with arguments reversed**.
     *
     * @param fromVertexId graph ID of the first Person vertex
     * @param toVertexId graph ID of the second Person vertex
     * @param since ISO-8601 date when the connection was established (optional)
     * @param strength connection strength 1–10 (default: 5)
     * @return pair of (forward edge, backward edge)
     * @throws IllegalArgumentException when endpoints are equal, missing, or not Person → Person.
     */
    fun connect(
        fromVertexId: GraphElementId,
        toVertexId: GraphElementId,
        since: String = "",
        strength: Int = 5,
    ): Pair<GraphEdge, GraphEdge> {
        strength.requireInRange(1, 10, "strength")
        requireDistinctEndpoints(fromVertexId, toVertexId, "fromVertexId", "toVertexId")
        requireEndpoint(fromVertexId, PersonLabel.label, "fromVertexId")
        requireEndpoint(toVertexId, PersonLabel.label, "toVertexId")
        val props = buildMap {
            if (since.isNotBlank()) put(KnowsLabel.since.name, since)
            put(KnowsLabel.strength.name, strength.toString())
        }
        val forward = ops.createEdge(fromVertexId, toVertexId, KnowsLabel.label, props)
        val backward = ops.createEdge(toVertexId, fromVertexId, KnowsLabel.label, props)
        log.debug { "Connected vertices [$fromVertexId ↔ $toVertexId] strength=$strength" }
        return forward to backward
    }

    /**
     * Creates a unidirectional FOLLOWS edge from [followerVertexId] to [followeeVertexId].
     *
     * Unlike [connect], FOLLOWS does not imply mutual acquaintance.
     *
     * @param followerVertexId graph ID of the Person who follows
     * @param followeeVertexId graph ID of the Person being followed
     * @throws IllegalArgumentException when endpoints are equal, missing, or not Person → Person.
     */
    fun follow(followerVertexId: GraphElementId, followeeVertexId: GraphElementId): GraphEdge {
        requireDistinctEndpoints(followerVertexId, followeeVertexId, "followerVertexId", "followeeVertexId")
        requireEndpoint(followerVertexId, PersonLabel.label, "followerVertexId")
        requireEndpoint(followeeVertexId, PersonLabel.label, "followeeVertexId")
        return ops.createEdge(followerVertexId, followeeVertexId, FollowsLabel.label, emptyMap())
    }

    /**
     * Creates a WORKS_AT edge from a Person vertex to a Company vertex.
     *
     * @param personVertexId graph ID of the Person vertex
     * @param companyVertexId graph ID of the Company vertex
     * @param role job title or role name (required, must not be blank)
     * @param startDate ISO-8601 employment start date (optional)
     * @param isCurrent whether employment is currently active (stored as "true"/"false")
     * @throws IllegalArgumentException when endpoints are missing or not Person → Company.
     */
    fun addWorkExperience(
        personVertexId: GraphElementId,
        companyVertexId: GraphElementId,
        role: String,
        startDate: String = "",
        isCurrent: Boolean = true,
    ): GraphEdge {
        role.requireNotBlank("role")
        requireEndpoint(personVertexId, PersonLabel.label, "personVertexId")
        requireEndpoint(companyVertexId, CompanyLabel.label, "companyVertexId")
        return ops.createEdge(
            personVertexId,
            companyVertexId,
            WorksAtLabel.label,
            buildMap {
                put(WorksAtLabel.role.name, role)
                if (startDate.isNotBlank()) put(WorksAtLabel.startDate.name, startDate)
                put(WorksAtLabel.isCurrent.name, isCurrent.toString())
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network traversal queries
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns direct (1st-degree) KNOWS connections of the given person.
     *
     * @param personVertexId graph ID of the seed Person vertex
     * @return list of directly connected Person vertices
     */
    fun getDirectConnections(personVertexId: GraphElementId): List<GraphVertex> {
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
    }

    /**
     * Returns all Person vertices reachable within [maxDegree] KNOWS hops from the seed.
     *
     * The seed vertex itself is always excluded from the result.
     *
     * @param personVertexId graph ID of the seed Person vertex
     * @param maxDegree maximum hop count (1..[MAX_TRAVERSAL_DEPTH])
     */
    fun getConnectionsWithinDegree(
        personVertexId: GraphElementId,
        maxDegree: Int = MAX_TRAVERSAL_DEPTH,
    ): List<GraphVertex> {
        maxDegree.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDegree")
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, maxDegree))
            .filter { it.id != personVertexId }
    }

    /**
     * Returns Person vertices reachable at exactly the [degree]-th hop from the seed.
     *
     * Vertices reachable at strictly shorter paths are excluded, matching the LinkedIn
     * "Nth-degree connection" semantic. The seed vertex itself is excluded.
     *
     * ## Algorithm
     * 1. Collect all vertices reachable within `degree` hops (excluding seed).
     * 2. Subtract vertices reachable within `degree - 1` hops (excluding seed).
     *
     * @param personVertexId graph ID of the seed Person vertex
     * @param degree target hop distance (1..[MAX_TRAVERSAL_DEPTH])
     */
    fun getNthDegreeConnections(personVertexId: GraphElementId, degree: Int): List<GraphVertex> {
        degree.requireInRange(1, MAX_TRAVERSAL_DEPTH, "degree")
        val allWithin = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree))
            .filter { it.id != personVertexId }
        val closerIds = if (degree > 1) {
            ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree - 1))
                .filter { it.id != personVertexId }
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }
        return allWithin.filter { it.id !in closerIds }
    }

    /**
     * Recommends connections using the Friend-of-a-Friend (FOAF) algorithm.
     *
     * **N+1 Warning**: this method issues one neighbor query per depth-2 candidate.
     * For large graphs, prefer a backend-native single-query implementation.
     *
     * ## Algorithm
     * 1. Collect direct (depth-1) connections of the seed.
     * 2. Collect depth-2 candidates; exclude seed and existing direct connections.
     * 3. For each candidate, count shared direct connections (mutual connection count).
     * 4. Return results sorted by mutual count descending, then by `personId` ascending
     *    for deterministic ordering when counts are tied.
     *
     * @param personVertexId graph ID of the seed Person vertex
     * @return ranked list of [ConnectionRecommendation]; empty if no FOAF candidates exist
     */
    fun recommendConnections(personVertexId: GraphElementId): List<ConnectionRecommendation> {
        val directFriends = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
        val directFriendIds = directFriends.map { it.id }.toSet()

        val candidates = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 2))
            .filter { it.id != personVertexId && it.id !in directFriendIds }

        return candidates.map { candidate ->
            val candidateFriends = ops.neighbors(candidate.id, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
                .filter { it.id != personVertexId }
            val mutualConnections = candidateFriends.filter { it.id in directFriendIds }
            ConnectionRecommendation(
                person = candidate,
                mutualConnectionCount = mutualConnections.size,
                mutualConnections = mutualConnections,
            )
        }
            .filter { it.mutualConnectionCount > 0 }
            .sortedWith(
                compareByDescending<ConnectionRecommendation> { it.mutualConnectionCount }
                    .thenBy { it.person.properties[PersonLabel.personId.name] as? String ?: "" }
            )
    }

    /**
     * Returns Person vertices that currently work at any company where [personVertexId] also works.
     *
     * The seed person is always excluded from the result.
     *
     * @param personVertexId graph ID of the seed Person vertex
     */
    fun findColleagues(personVertexId: GraphElementId): List<GraphVertex> {
        val companies = ops.neighbors(personVertexId, NeighborOptions(WorksAtLabel.label, Direction.OUTGOING, 1))
        return companies
            .flatMap { company ->
                ops.neighbors(company.id, NeighborOptions(WorksAtLabel.label, Direction.INCOMING, 1))
            }
            .filter { it.id != personVertexId }
            .distinctBy { it.id }
    }

    /**
     * Returns the shortest KNOWS path from [fromVertexId] to [toVertexId], or `null` if none exists.
     *
     * @param fromVertexId graph ID of the start Person vertex
     * @param toVertexId graph ID of the target Person vertex
     */
    fun findConnectionPath(fromVertexId: GraphElementId, toVertexId: GraphElementId): GraphPath? {
        return ops.shortestPath(
            fromVertexId,
            toVertexId,
            PathOptions(edgeLabel = KnowsLabel.label, direction = Direction.OUTGOING),
        )
    }

    /**
     * Returns all KNOWS paths from [fromVertexId] to [toVertexId] up to [maxDepth] hops.
     *
     * @param fromVertexId graph ID of the start Person vertex
     * @param toVertexId graph ID of the target Person vertex
     * @param maxDepth maximum path length in hops (1..[MAX_TRAVERSAL_DEPTH])
     */
    fun findAllConnectionPaths(
        fromVertexId: GraphElementId,
        toVertexId: GraphElementId,
        maxDepth: Int = 5,
    ): List<GraphPath> {
        maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")
        return ops.allPaths(
            fromVertexId,
            toVertexId,
            PathOptions(edgeLabel = KnowsLabel.label, maxDepth = maxDepth, direction = Direction.OUTGOING),
        )
    }

    /**
     * Returns the shared direct (1st-degree) KNOWS connections of two Person vertices.
     *
     * @param vertexId1 graph ID of the first Person vertex
     * @param vertexId2 graph ID of the second Person vertex
     * @return list of Person vertices directly connected to both
     */
    fun findMutualConnections(vertexId1: GraphElementId, vertexId2: GraphElementId): List<GraphVertex> {
        val friends1 = ops.neighbors(vertexId1, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
            .map { it.id }
            .toSet()
        return ops.neighbors(vertexId2, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
            .filter { it.id in friends1 }
    }

    private fun requireEndpoint(id: GraphElementId, expectedLabel: String, parameterName: String): GraphVertex {
        val vertex = ops.findVertexById(id).requireNotNull(parameterName)
        vertex.label.requireEquals(expectedLabel, "$parameterName.label")
        return vertex
    }

    private fun requireDistinctEndpoints(
        first: GraphElementId,
        second: GraphElementId,
        firstName: String,
        secondName: String,
    ) {
        if (first == second) {
            throw IllegalArgumentException("$firstName must differ from $secondName")
        }
    }
}

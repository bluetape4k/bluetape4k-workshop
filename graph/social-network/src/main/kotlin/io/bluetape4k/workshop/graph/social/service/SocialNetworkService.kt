package io.bluetape4k.workshop.graph.social.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphPath
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PathOptions
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.requireEndpoint
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.graph.social.model.ConnectionRecommendation
import io.bluetape4k.workshop.graph.social.schema.CompanyLabel
import io.bluetape4k.workshop.graph.social.schema.FollowsLabel
import io.bluetape4k.workshop.graph.social.schema.KnowsLabel
import io.bluetape4k.workshop.graph.social.schema.PersonLabel
import io.bluetape4k.workshop.graph.social.schema.WorksAtLabel

/**
 * LinkedIn 스타일 social network 작업을 수행하는 블로킹 그래프 서비스입니다.
 *
 * 전달된 [GraphOperations] backend 위의 named graph를 사용해 person/company node,
 * 양방향 connection, follow, work experience, network traversal 알고리즘을 관리합니다.
 *
 * ## 동작 / 계약
 * - named graph가 존재하도록 다른 메서드보다 먼저 [initialize]를 한 번 호출해야 합니다.
 * - 정점 변경 메서드([addPerson], [addCompany])는 멱등입니다. 도메인 키로 찾고 없으면 생성합니다.
 * - [connect]는 양방향 connection을 두 개의 방향성 간선(A -> B, B -> A)으로 저장합니다.
 *   pair마다 한 번만 호출하고, 인자를 뒤집어 다시 호출하지 않습니다.
 * - [follow]는 단방향입니다. follower에서 followee로 향하는 간선 하나만 만듭니다.
 * - 간선 변경 메서드는 정점 누락과 source/target label 불일치를 [IllegalArgumentException]으로 거부합니다.
 * - [recommendConnections]는 후보마다 O(N) neighbor 조회를 수행하므로 큰 그래프에서는 주의해서 사용합니다.
 * - [getNthDegreeConnections]는 전체 집합과 더 가까운 집합 양쪽에서 seed 정점을 제외합니다.
 *
 * ## 사용 예
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
         * network query에 허용하는 최대 순회 깊이입니다.
         * LinkedIn의 "6 degrees of separation"에 해당하는 상한입니다.
         */
        const val MAX_TRAVERSAL_DEPTH: Int = 6
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * named graph가 존재하도록 보장합니다. 여러 번 호출해도 안전하며, 이미 생성되어 있으면 no-op입니다.
     */
    fun initialize() {
        if (!ops.graphExists(graphName)) {
            log.debug { "Creating graph: $graphName" }
            ops.createGraph(graphName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 정점 변경 메서드(도메인 키 기준 find-or-create)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [personId]로 기존 Person 정점을 찾거나 새로 만듭니다.
     *
     * @param personId 안정적인 도메인 키입니다. username이나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
     * @param name 표시 이름입니다.
     * @param title 직무 title입니다. 선택 값입니다.
     * @param location 도시 또는 지역입니다. 선택 값입니다.
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
     * [companyId]로 기존 Company 정점을 찾거나 새로 만듭니다.
     *
     * @param companyId 안정적인 도메인 키입니다. 내부 구조를 드러내지 않는 문자열입니다.
     * @param name 회사 표시 이름입니다.
     * @param industry 산업 분야입니다. 선택 값입니다.
     * @param location 본사 도시 또는 지역입니다. 선택 값입니다.
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
    // 간선 변경 메서드
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 두 Person 정점 사이에 양방향 `KNOWS` connection을 설정합니다.
     *
     * 동일한 속성을 가진 두 개의 방향성 간선(from -> to, to -> from)으로 저장합니다.
     * 이 메서드는 pair마다 한 번만 호출합니다. **인자를 뒤집어 다시 호출하지 않습니다.**
     *
     * @param fromVertexId 첫 번째 Person 정점의 graph ID입니다.
     * @param toVertexId 두 번째 Person 정점의 graph ID입니다.
     * @param since connection이 성립된 ISO-8601 날짜입니다. 선택 값입니다.
     * @param strength connection 강도입니다. 범위는 1-10이고 기본값은 5입니다.
     * @return (정방향 간선, 역방향 간선) pair입니다.
     * @throws IllegalArgumentException endpoint가 같거나, 누락되었거나, Person -> Person 관계가 아니면 발생합니다.
     */
    fun connect(
        fromVertexId: GraphElementId,
        toVertexId: GraphElementId,
        since: String = "",
        strength: Int = 5,
    ): Pair<GraphEdge, GraphEdge> {
        strength.requireInRange(1, 10, "strength")
        requireDistinctEndpoints(fromVertexId, toVertexId, "fromVertexId", "toVertexId")
        ops.requireEndpoint(fromVertexId, PersonLabel.label, "fromVertexId")
        ops.requireEndpoint(toVertexId, PersonLabel.label, "toVertexId")
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
     * [followerVertexId]에서 [followeeVertexId]로 향하는 단방향 `FOLLOWS` 간선을 만듭니다.
     *
     * [connect]와 달리 `FOLLOWS`는 상호 acquaintance를 뜻하지 않습니다.
     *
     * @param followerVertexId follow하는 Person의 graph ID입니다.
     * @param followeeVertexId follow 대상 Person의 graph ID입니다.
     * @throws IllegalArgumentException endpoint가 같거나, 누락되었거나, Person -> Person 관계가 아니면 발생합니다.
     */
    fun follow(followerVertexId: GraphElementId, followeeVertexId: GraphElementId): GraphEdge {
        requireDistinctEndpoints(followerVertexId, followeeVertexId, "followerVertexId", "followeeVertexId")
        ops.requireEndpoint(followerVertexId, PersonLabel.label, "followerVertexId")
        ops.requireEndpoint(followeeVertexId, PersonLabel.label, "followeeVertexId")
        return ops.createEdge(followerVertexId, followeeVertexId, FollowsLabel.label, emptyMap())
    }

    /**
     * Person 정점에서 Company 정점으로 향하는 `WORKS_AT` 간선을 만듭니다.
     *
     * @param personVertexId Person 정점의 graph ID입니다.
     * @param companyVertexId Company 정점의 graph ID입니다.
     * @param role 직무 title 또는 role 이름입니다. 필수이며 blank이면 안 됩니다.
     * @param startDate ISO-8601 재직 시작일입니다. 선택 값입니다.
     * @param isCurrent 현재 재직 여부입니다. `"true"`/`"false"` 문자열로 저장합니다.
     * @throws IllegalArgumentException endpoint가 없거나 Person -> Company 관계가 아니면 발생합니다.
     */
    fun addWorkExperience(
        personVertexId: GraphElementId,
        companyVertexId: GraphElementId,
        role: String,
        startDate: String = "",
        isCurrent: Boolean = true,
    ): GraphEdge {
        role.requireNotBlank("role")
        ops.requireEndpoint(personVertexId, PersonLabel.label, "personVertexId")
        ops.requireEndpoint(companyVertexId, CompanyLabel.label, "companyVertexId")
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
    // Network 순회 query
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 지정한 Person의 direct(1st-degree) `KNOWS` connection을 반환합니다.
     *
     * @param personVertexId seed Person 정점의 graph ID입니다.
     * @return 직접 연결된 Person 정점 목록입니다.
     */
    fun getDirectConnections(personVertexId: GraphElementId): List<GraphVertex> {
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
    }

    /**
     * seed에서 [maxDegree] `KNOWS` hop 안에 도달할 수 있는 모든 Person 정점을 반환합니다.
     *
     * seed 정점 자체는 항상 결과에서 제외합니다.
     *
     * @param personVertexId seed Person 정점의 graph ID입니다.
     * @param maxDegree 최대 hop 수입니다. 1..[MAX_TRAVERSAL_DEPTH] 범위여야 합니다.
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
     * seed에서 정확히 [degree]번째 hop에 도달할 수 있는 Person 정점을 반환합니다.
     *
     * 더 짧은 path로 도달할 수 있는 정점은 제외해 LinkedIn의 "Nth-degree connection"
     * 의미론과 맞춥니다. seed 정점 자체도 제외합니다.
     *
     * ## 알고리즘
     * 1. `degree` hop 안에 도달 가능한 모든 정점을 수집합니다(seed 제외).
     * 2. `degree - 1` hop 안에 도달 가능한 정점을 뺍니다(seed 제외).
     *
     * @param personVertexId seed Person 정점의 graph ID입니다.
     * @param degree 목표 hop 거리입니다. 1..[MAX_TRAVERSAL_DEPTH] 범위여야 합니다.
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
     * FOAF(Friend-of-a-Friend) 알고리즘으로 connection을 추천합니다.
     *
     * **N+1 경고**: 이 메서드는 depth-2 후보마다 neighbor 조회 1회를 실행합니다.
     * 큰 그래프에서는 backend-native single-query 구현을 우선합니다.
     *
     * ## 알고리즘
     * 1. seed의 direct(depth-1) connection을 수집합니다.
     * 2. depth-2 후보를 수집하고, seed와 기존 direct connection을 제외합니다.
     * 3. 후보마다 공유 direct connection 수(mutual connection count)를 셉니다.
     * 4. mutual count 내림차순, `personId` 오름차순으로 정렬해 count가 같을 때도 결정적인 순서를 유지합니다.
     *
     * @param personVertexId seed Person 정점의 graph ID입니다.
     * @return 순위화된 [ConnectionRecommendation] 목록입니다. FOAF 후보가 없으면 비어 있습니다.
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
     * [personVertexId]도 근무 중인 회사에서 현재 함께 일하는 Person 정점을 반환합니다.
     *
     * seed Person은 항상 결과에서 제외합니다.
     *
     * @param personVertexId seed Person 정점의 graph ID입니다.
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
     * [fromVertexId]에서 [toVertexId]까지의 최단 `KNOWS` path를 반환합니다. 없으면 `null`입니다.
     *
     * @param fromVertexId 시작 Person 정점의 graph ID입니다.
     * @param toVertexId 대상 Person 정점의 graph ID입니다.
     */
    fun findConnectionPath(fromVertexId: GraphElementId, toVertexId: GraphElementId): GraphPath? {
        return ops.shortestPath(
            fromVertexId,
            toVertexId,
            PathOptions(edgeLabel = KnowsLabel.label, direction = Direction.OUTGOING),
        )
    }

    /**
     * [fromVertexId]에서 [toVertexId]까지 [maxDepth] hop 이하의 모든 `KNOWS` path를 반환합니다.
     *
     * @param fromVertexId 시작 Person 정점의 graph ID입니다.
     * @param toVertexId 대상 Person 정점의 graph ID입니다.
     * @param maxDepth 최대 path 길이입니다. hop 기준이며 1..[MAX_TRAVERSAL_DEPTH] 범위여야 합니다.
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
     * 두 Person 정점이 공유하는 direct(1st-degree) `KNOWS` connection을 반환합니다.
     *
     * @param vertexId1 첫 번째 Person 정점의 graph ID입니다.
     * @param vertexId2 두 번째 Person 정점의 graph ID입니다.
     * @return 두 정점 모두와 직접 연결된 Person 정점 목록입니다.
     */
    fun findMutualConnections(vertexId1: GraphElementId, vertexId2: GraphElementId): List<GraphVertex> {
        val friends1 = ops.neighbors(vertexId1, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
            .map { it.id }
            .toSet()
        return ops.neighbors(vertexId2, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
            .filter { it.id in friends1 }
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

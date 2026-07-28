package io.bluetape4k.workshop.graph.recommendation.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

/**
 * e-commerce 상품 추천과 social follow 추천을 제공하는 coroutine 기반 그래프 서비스입니다.
 *
 * coroutine context에서 non-blocking으로 사용할 수 있도록 [RecommendationService] API를
 * suspend 반환 형태로 대응시킵니다.
 *
 * ## 동작 / 계약
 * - named graph가 존재하도록 다른 메서드보다 먼저 [initialize]를 한 번 호출해야 합니다.
 * - 정점 변경 메서드([addUser], [addProduct])는 멱등입니다. 도메인 키로 찾고 없으면 생성합니다.
 * - [purchase]와 [follow]는 멱등이 아닙니다. 반복 호출하면 간선이 추가로 생성됩니다.
 * - [purchase]는 User -> Product endpoint를 검증하고, [follow]는 User -> User endpoint를 검증합니다.
 * - [recommendProducts]는 [userVertexId]를 찾을 수 없거나 구매가 없으면 `emptyList()`를 반환합니다.
 * - [recommendFollows]는 [userVertexId]를 찾을 수 없거나 follow가 없으면 `emptyList()`를 반환합니다.
 * - [follow]는 follower와 followee가 같으면 [IllegalArgumentException]을 던집니다.
 * - suspend 호출을 `runCatching`으로 감싸지 않습니다. [kotlinx.coroutines.CancellationException]은 전파되어야 합니다.
 *   이 클래스는 `CancellationException`을 올바르게 전파합니다. 내부 [GraphSuspendOperations]
 *   구현도 같은지는 각 구현의 coroutine 계약에 따릅니다.
 *
 * ## 알려진 제한(워크숍 demo 범위)
 * - **N+1 traversal**: [recommendProducts]는 seed 상품마다 neighbor 조회 1회와 co-buyer마다
 *   조회 1회를 실행합니다. [recommendFollows]는 direct follow마다 outgoing neighbor 조회 1회를 실행합니다.
 *   `limit` 인자는 출력 개수만 제한하며 I/O 호출 수를 제한하지 않습니다. 큰 그래프에서는 native
 *   Cypher/Gremlin query로 교체합니다.
 * - **[initialize]의 TOCTOU**: `graphExists -> createGraph` 검사는 원자적이지 않고 단일 instance
 *   배포를 가정합니다. 동시 호출자는 중복 생성을 시도할 수 있습니다. 이 demo에서는 허용하지만,
 *   production code는 advisory locking 또는 server-side upsert 의미론을 사용해야 합니다.
 *
 * ## 사용 예
 * ```kotlin
 * val service = RecommendationSuspendService(ops, "recommendation")
 * service.initialize()
 * val alice = service.addUser("alice", "Alice")
 * val laptop = service.addProduct("laptop", "Laptop", category = "Electronics")
 * service.purchase(alice.id, laptop.id, rating = 5)
 * val recs = service.recommendProducts(alice.id)
 * ```
 */
class RecommendationSuspendService(
    private val ops: GraphSuspendOperations,
    private val graphName: String,
) {
    init {
        graphName.requireNotBlank("graphName")
    }

    companion object : KLoggingChannel()

    /**
     * named graph가 존재하도록 보장합니다. 여러 번 호출해도 안전하며, 이미 생성되어 있으면 no-op입니다.
     */
    suspend fun initialize() {
        if (!ops.graphExists(graphName)) {
            log.debug { "Creating graph: $graphName" }
            ops.createGraph(graphName)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 정점 변경 메서드(도메인 키 기준 find-or-create)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [userId]로 기존 User 정점을 찾거나 새로 만듭니다.
     *
     * @param userId 안정적인 도메인 키입니다. username이나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
     * @param name 표시 이름입니다.
     */
    suspend fun addUser(userId: String, name: String): GraphVertex {
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
     * [productId]로 기존 Product 정점을 찾거나 새로 만듭니다.
     *
     * @param productId 안정적인 도메인 키입니다. SKU나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
     * @param name 표시 이름입니다.
     * @param category 상품 category입니다. 선택 값입니다.
     */
    suspend fun addProduct(productId: String, name: String, category: String = ""): GraphVertex {
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
    // 간선 변경 메서드
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * User 정점에서 Product 정점으로 향하는 `PURCHASED` 간선을 만듭니다.
     *
     * 멱등이 아닙니다. 반복 호출하면 간선이 추가로 생성됩니다.
     *
     * @param userVertexId User 정점의 graph ID입니다.
     * @param productVertexId Product 정점의 graph ID입니다.
     * @param rating 구매 평점입니다. 범위는 0-5이며, 0은 미평가를 뜻하고 0보다 클 때만 저장합니다.
     * @param purchasedAt ISO-8601 타임스탬프입니다. 선택 값입니다.
     * @throws IllegalArgumentException endpoint가 없거나 User -> Product 관계가 아니면 발생합니다.
     */
    suspend fun purchase(
        userVertexId: GraphElementId,
        productVertexId: GraphElementId,
        rating: Int = 0,
        purchasedAt: String = "",
    ): GraphEdge {
        rating.requireInRange(0, 5, "rating")
        requireEndpoint(userVertexId, UserLabel.label, "userVertexId")
        requireEndpoint(productVertexId, ProductLabel.label, "productVertexId")
        val props = buildMap {
            if (rating > 0) put(PurchasedLabel.rating.name, rating.toString())
            if (purchasedAt.isNotBlank()) put(PurchasedLabel.purchasedAt.name, purchasedAt)
        }
        return ops.createEdge(userVertexId, productVertexId, PurchasedLabel.label, props)
    }

    /**
     * [followerVertexId]에서 [followeeVertexId]로 향하는 단방향 `FOLLOWS` 간선을 만듭니다.
     *
     * 멱등이 아닙니다. 반복 호출하면 간선이 추가로 생성됩니다.
     *
     * @param followerVertexId follow하는 User의 graph ID입니다.
     * @param followeeVertexId follow 대상 User의 graph ID입니다.
     * @throws IllegalArgumentException endpoint가 같거나, 누락되었거나, User -> User 관계가 아니면 발생합니다.
     */
    suspend fun follow(followerVertexId: GraphElementId, followeeVertexId: GraphElementId): GraphEdge {
        requireDistinctEndpoints(followerVertexId, followeeVertexId, "followerVertexId", "followeeVertexId")
        requireEndpoint(followerVertexId, UserLabel.label, "followerVertexId")
        requireEndpoint(followeeVertexId, UserLabel.label, "followeeVertexId")
        return ops.createEdge(followerVertexId, followeeVertexId, FollowsLabel.label, emptyMap())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 추천 조회
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * seed 사용자의 상품을 산 co-buyer들이 구매한 상품을 반환하며,
     * 서로 다른 co-buyer 수(score) 기준으로 순위를 매깁니다.
     *
     * 이미 구매한 상품은 결과에서 제외합니다.
     *
     * **N+1 경고**: 상품마다 neighbor 조회 1회와 co-buyer마다 조회 1회를 실행합니다.
     *
     * @param userVertexId [addUser]가 반환한 GraphVertex.id입니다.
     * @param limit 1..[MAX_RECOMMENDATION_LIMIT] 범위의 반환 상한입니다.
     * @return 순위화된 [ProductRecommendation] 목록입니다. User를 찾을 수 없거나 구매가 없으면 `emptyList()`입니다.
     */
    suspend fun recommendProducts(userVertexId: GraphElementId, limit: Int = DEFAULT_RECOMMENDATION_LIMIT): List<ProductRecommendation> {
        return explainProductRecommendations(userVertexId, limit).map { it.recommendation }
    }

    /**
     * 각 score를 설명하는 구체적인 co-buyer path와 제외 규칙을 포함한 상품 추천을 반환합니다.
     */
    suspend fun explainProductRecommendations(
        userVertexId: GraphElementId,
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT,
    ): List<ExplainedProductRecommendation> {
        limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

        val myProducts = ops.neighbors(userVertexId, NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1))
            .toList()
        if (myProducts.isEmpty()) return emptyList()
        val myProductIds = myProducts.map { it.id }.toSet()
        val excludedCandidates = myProducts.map { CandidateExclusion(it.id, ALREADY_PURCHASED) }

        // candidateMap: candidateProductId -> (candidateVertex, evidence path)
        val candidateMap = mutableMapOf<GraphElementId, Pair<GraphVertex, MutableList<ProductEvidencePath>>>()

        for (product in myProducts) {
            val coBuyers = ops.neighbors(product.id, NeighborOptions(PurchasedLabel.label, Direction.INCOMING, 1))
                .toList()
                .filter { it.id != userVertexId }

            for (coBuyer in coBuyers) {
                val theirProducts = ops.neighbors(
                    coBuyer.id,
                    NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1)
                ).toList()
                    .filter { it.id !in myProductIds }

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
     * 2-hop `FOLLOWS` 순회(FOAF)를 기반으로 follow할 User를 추천합니다.
     *
     * 이미 follow한 User와 seed 사용자 자신은 결과에서 제외합니다.
     *
     * **N+1 경고**: direct follow마다 OUTGOING neighbor 조회 1회를 실행합니다.
     *
     * @param userVertexId [addUser]가 반환한 GraphVertex.id입니다.
     * @param limit 1..[MAX_RECOMMENDATION_LIMIT] 범위의 반환 상한입니다.
     * @return 순위화된 [FollowRecommendation] 목록입니다. User를 찾을 수 없거나 follow가 없으면 `emptyList()`입니다.
     */
    suspend fun recommendFollows(userVertexId: GraphElementId, limit: Int = DEFAULT_RECOMMENDATION_LIMIT): List<FollowRecommendation> {
        return explainFollowRecommendations(userVertexId, limit).map { it.recommendation }
    }

    /**
     * FOAF 증거와 제외된 후보 규칙을 포함한 follow 추천을 반환합니다.
     */
    suspend fun explainFollowRecommendations(
        userVertexId: GraphElementId,
        limit: Int = DEFAULT_RECOMMENDATION_LIMIT,
    ): List<ExplainedFollowRecommendation> {
        limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

        val myFollows = ops.neighbors(userVertexId, NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 1))
            .toList()
        if (myFollows.isEmpty()) return emptyList()
        val myFollowIds = myFollows.map { it.id }.toSet()
        val excludedCandidates = myFollows.map { CandidateExclusion(it.id, ALREADY_FOLLOWED) }.toMutableList()
        val candidateMap = mutableMapOf<GraphElementId, Pair<GraphVertex, MutableList<FollowEvidencePath>>>()

        for (intermediary in myFollows) {
            val candidates = ops.neighbors(
                intermediary.id,
                NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 1)
            ).toList()

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

    private suspend fun requireEndpoint(id: GraphElementId, expectedLabel: String, parameterName: String): GraphVertex {
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

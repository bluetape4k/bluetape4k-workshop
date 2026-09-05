package io.bluetape4k.workshop.graph.abuser.service

import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecution
import io.bluetape4k.graph.algo.provider.GraphAlgorithmExecutionObserver
import io.bluetape4k.graph.algo.provider.GraphAlgorithmId
import io.bluetape4k.graph.algo.provider.GraphAlgorithmProviderPolicy
import io.bluetape4k.graph.algo.provider.GraphAlgorithmProviderSelector
import io.bluetape4k.graph.model.CycleOptions
import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphCycle
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PageRankOptions
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.repository.requireEndpoint
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.graph.abuser.model.AbuseCluster
import io.bluetape4k.workshop.graph.abuser.model.AbuserAlgorithmExecution
import io.bluetape4k.workshop.graph.abuser.model.AbusePath
import io.bluetape4k.workshop.graph.abuser.model.IdentifierEdgeLabel
import io.bluetape4k.workshop.graph.abuser.model.SuspiciousUserRanking
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.withIndex

/**
 * 어뷰저 감지용 코루틴 기반 그래프 서비스입니다.
 *
 * 논블로킹 사용을 위해 [AbuserDetectionService] API를 suspend/Flow 반환 타입으로 반영합니다.
 * 코루틴 context에서 사용합니다. 많은 결과를 반환할 수 있는 읽기 중심 작업은 모두
 * cold [Flow]를 노출하므로 호출자가 배압과 취소를 제어합니다.
 *
 * ## 동작 / 계약
 * - named graph 존재를 보장하려면 다른 메서드보다 먼저 [initialize]를 호출해야 합니다.
 * - 정점 변경 메서드는 멱등입니다. 도메인 키로 기존 정점을 찾거나 새로 만듭니다.
 * - 간선 변경 메서드는 [GraphSuspendOperations.createEdge]를 직접 호출하므로 호출자는 중복 링크를 피해야 합니다.
 * - [findAbuseCluster]는 seed 사용자를 [AbuseCluster.users]에서 제외합니다.
 * - [rankSuspiciousUsers]는 PageRank로 순위화한 User 정점의 cold [Flow]를 반환하며 순위는 1부터 시작합니다.
 * - [rankSuspiciousUsersWithExecution]은 Flow 수집을 완료한 뒤 점수와 실제 실행 경로를 함께 반환합니다.
 * - suspend 호출을 `runCatching`으로 감싸면 안 됩니다. [kotlinx.coroutines.CancellationException]은 전파되어야 합니다.
 *
 * ## 사용 예
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
    private val executionObserver: GraphAlgorithmExecutionObserver = GraphAlgorithmExecutionObserver.Noop,
) {

    init {
        graphName.requireNotBlank("graphName")
    }

    companion object : KLoggingChannel() {
        /**
         * 역방향 순회를 위해 식별자 정점 레이블을 [IdentifierEdgeLabel]로 매핑합니다.
         *
         * [findAbuseCluster]에서 식별자 정점에서 연결된 사용자로 돌아갈 때 따라갈 INCOMING 간선 레이블을
         * 찾는 데 사용합니다.
         */
        private val VERTEX_LABEL_TO_EDGE_LABEL: Map<String, IdentifierEdgeLabel> = mapOf(
            DeviceLabel.label        to IdentifierEdgeLabel.USES_DEVICE,
            IpAddressLabel.label     to IdentifierEdgeLabel.USES_IP,
            PhoneNumberLabel.label   to IdentifierEdgeLabel.HAS_PHONE,
            PaymentMethodLabel.label to IdentifierEdgeLabel.USES_PAYMENT,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 생명주기
    // ─────────────────────────────────────────────────────────────────────────

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
    // 정점 변경 메서드(도메인 키 기준 조회 또는 생성)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * [userId]로 기존 User 정점을 찾거나 새로 만듭니다.
     *
     * @param userId 안정적인 사용자 식별자입니다(불투명 문자열, 예: UUID).
     * @param country ISO-3166-1 alpha-2 국가 코드입니다.
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
     * [deviceId]로 기존 Device 정점을 찾거나 새로 만듭니다.
     *
     * @param deviceId 고유 디바이스 fingerprint입니다.
     * @param platform OS/platform 문자열입니다. 예: `"android"`, `"ios"`, `"web"`.
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
     * [ip]로 기존 IpAddress 정점을 찾거나 새로 만듭니다.
     *
     * @param ip IPv4 또는 IPv6 주소 문자열입니다.
     */
    suspend fun addIpAddress(ip: String): GraphVertex {
        ip.requireNotBlank("ip")
        return ops.findVerticesByLabel(IpAddressLabel.label, mapOf(IpAddressLabel.ip.name to ip))
            .firstOrNull()
            ?: ops.createVertex(IpAddressLabel.label, mapOf(IpAddressLabel.ip.name to ip))
    }

    /**
     * 해시 처리된 전화번호로 기존 PhoneNumber 정점을 찾거나 새로 만듭니다.
     *
     * **보안**: [hashedPhone] MUST be an E.164-format SHA-256 hex hash.
     * 원시 전화번호는 이 메서드에 전달하면 안 됩니다.
     *
     * @param hashedPhone 전화번호의 E.164 SHA-256 hex입니다.
     */
    suspend fun addPhoneNumber(hashedPhone: String): GraphVertex {
        hashedPhone.requireNotBlank("hashedPhone")
        return ops.findVerticesByLabel(PhoneNumberLabel.label, mapOf(PhoneNumberLabel.phone.name to hashedPhone))
            .firstOrNull()
            ?: ops.createVertex(PhoneNumberLabel.label, mapOf(PhoneNumberLabel.phone.name to hashedPhone))
    }

    /**
     * [paymentToken]으로 기존 PaymentMethod 정점을 찾거나 새로 만듭니다.
     *
     * **보안**: [paymentToken] 반드시 PCI-safe processor token이어야 합니다.
     * 원시 PAN 또는 CVV는 이 메서드에 전달하면 안 됩니다.
     *
     * @param paymentToken processor가 발급한 결제 토큰입니다(원시 PAN 또는 CVV 아님).
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
    // 간선 변경 메서드
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * `USES_DEVICE` 간선으로 사용자를 디바이스에 연결합니다.
     *
     * @param userVertexId User 정점의 그래프 ID입니다.
     * @param deviceVertexId Device 정점의 그래프 ID입니다.
     * @param occurredAt 이 디바이스에서 처음 로그인한 ISO-8601 타임스탬프입니다.
     */
    suspend fun linkDevice(
        userVertexId: GraphElementId,
        deviceVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        ops.requireEndpoint(userVertexId, UserLabel.label, "userVertexId")
        ops.requireEndpoint(deviceVertexId, DeviceLabel.label, "deviceVertexId")
        return ops.createEdge(
            userVertexId,
            deviceVertexId,
            UsesDeviceLabel.label,
            mapOf(UsesDeviceLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * `USES_IP` 간선으로 사용자를 IP 주소에 연결합니다.
     *
     * @param userVertexId User 정점의 그래프 ID입니다.
     * @param ipVertexId IpAddress 정점의 그래프 ID입니다.
     * @param occurredAt 최초 관측 접속의 ISO-8601 타임스탬프입니다.
     */
    suspend fun linkIp(
        userVertexId: GraphElementId,
        ipVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        ops.requireEndpoint(userVertexId, UserLabel.label, "userVertexId")
        ops.requireEndpoint(ipVertexId, IpAddressLabel.label, "ipVertexId")
        return ops.createEdge(
            userVertexId,
            ipVertexId,
            UsesIpLabel.label,
            mapOf(UsesIpLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * `HAS_PHONE` 간선으로 사용자를 전화번호에 연결합니다.
     *
     * @param userVertexId User 정점의 그래프 ID입니다.
     * @param phoneVertexId PhoneNumber 정점의 그래프 ID입니다.
     * @param occurredAt 최초 연결 시점의 ISO-8601 타임스탬프입니다.
     */
    suspend fun linkPhone(
        userVertexId: GraphElementId,
        phoneVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        ops.requireEndpoint(userVertexId, UserLabel.label, "userVertexId")
        ops.requireEndpoint(phoneVertexId, PhoneNumberLabel.label, "phoneVertexId")
        return ops.createEdge(
            userVertexId,
            phoneVertexId,
            HasPhoneLabel.label,
            mapOf(HasPhoneLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * `USES_PAYMENT` 간선으로 사용자를 결제 수단에 연결합니다.
     *
     * @param userVertexId User 정점의 그래프 ID입니다.
     * @param paymentVertexId PaymentMethod 정점의 그래프 ID입니다.
     * @param occurredAt 최초 결제 시도 시점의 ISO-8601 타임스탬프입니다.
     */
    suspend fun linkPayment(
        userVertexId: GraphElementId,
        paymentVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        ops.requireEndpoint(userVertexId, UserLabel.label, "userVertexId")
        ops.requireEndpoint(paymentVertexId, PaymentMethodLabel.label, "paymentVertexId")
        return ops.createEdge(
            userVertexId,
            paymentVertexId,
            UsesPaymentLabel.label,
            mapOf(UsesPaymentLabel.occurredAt.name to occurredAt),
        )
    }

    /**
     * [referrerVertexId]에서 [referredVertexId]로 향하는 추천 관계를 기록합니다.
     *
     * @param referrerVertexId 추천한 User 정점의 그래프 ID입니다.
     * @param referredVertexId 추천받은 User 정점의 그래프 ID입니다.
     * @param occurredAt 추천 이벤트의 ISO-8601 타임스탬프입니다.
     */
    suspend fun linkReferral(
        referrerVertexId: GraphElementId,
        referredVertexId: GraphElementId,
        occurredAt: String,
    ): GraphEdge {
        occurredAt.requireNotBlank("occurredAt")
        ops.requireEndpoint(referrerVertexId, UserLabel.label, "referrerVertexId")
        ops.requireEndpoint(referredVertexId, UserLabel.label, "referredVertexId")
        return ops.createEdge(
            referrerVertexId,
            referredVertexId,
            ReferredByLabel.label,
            mapOf(ReferredByLabel.occurredAt.name to occurredAt),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 조회 알고리즘
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 지정한 seed 사용자와 하나 이상의 식별자를 공유하는 모든 사용자를 찾습니다.
     *
     * 알고리즘:
     * 1. [seedUserId]에서 OUTGOING 식별자 간선으로 도달할 수 있는 모든 식별자 정점을 수집합니다.
     * 2. 각 식별자 정점에서 같은 식별자 간선 유형을 따라 INCOMING 사용자를 수집합니다.
     * 3. 결과에서 seed 사용자 자신을 제거합니다.
     *
     * @param seedUserId seed 사용자의 그래프 정점 ID입니다.
     * @return [AbuseCluster.users]에서 seed 사용자를 제외한 [AbuseCluster]입니다.
     *         seed 사용자가 없으면 빈 클러스터를 반환합니다.
     */
    suspend fun findAbuseCluster(seedUserId: GraphElementId): AbuseCluster {
        val emptyCluster = AbuseCluster(seedUserId, emptyList(), emptyList())

        if (ops.findVertexById(seedUserId) == null) {
            log.warn { "findAbuseCluster: seed user vertex not found [seedUserId=$seedUserId, graphName=$graphName] — returning empty cluster" }
            return emptyCluster
        }

        // 1단계: OUTGOING 식별자 간선을 순회해 공유 식별자를 모읍니다.
        val seedIdentifiers = buildList {
            for (edgeLabel in IdentifierEdgeLabel.all) {
                currentCoroutineContext().ensureActive()
                addAll(
                    ops.neighbors(seedUserId, NeighborOptions(edgeLabel.value, Direction.OUTGOING, 1))
                        .toList()
                )
            }
        }

        if (seedIdentifiers.isEmpty()) {
            return emptyCluster
        }

        // 2단계: 각 식별자에서 역방향으로 순회해 함께 연결된 사용자를 찾습니다.
        val clusterUsers = mutableListOf<GraphVertex>()
        for (identifierVertex in seedIdentifiers) {
            currentCoroutineContext().ensureActive()
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
     * 의심 입력으로 사용하는 사용자의 outgoing 식별자 경로를 cold [Flow]로 반환합니다.
     *
     * 각 [AbusePath]는 식별자 정점 하나와 사용자를 그 정점에 연결하는 간선 유형을 설명합니다.
     *
     * @param userId 설명할 사용자의 그래프 정점 ID입니다.
     * @return outgoing [AbusePath] 항목의 cold [Flow]입니다. 사용자에게 식별자 간선이 없으면 비어 있습니다.
     */
    fun explainSuspicion(userId: GraphElementId): Flow<AbusePath> = flow {
        for (edgeLabel in IdentifierEdgeLabel.all) {
            currentCoroutineContext().ensureActive()
            ops.findEdgesByStartId(userId, edgeLabel.value).collect { edge ->
                emit(AbusePath(identifierVertexId = edge.endId, edgeLabel = edgeLabel))
            }
        }
    }

    /**
     * 감지된 추천 루프를 cold [Flow]로 반환합니다. 추천 루프는 REFERRED_BY 간선을 통한 User 정점 사이의 cycle입니다.
     *
     * @param maxDepth cycle 검색 시 최대 순회 깊이입니다(기본값 6).
     * @param maxCycles 반환할 최대 cycle 수입니다(기본값 100).
     * @return [GraphCycle]의 cold [Flow]입니다. 루프가 없으면 비어 있습니다.
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
     * PageRank 점수 내림차순으로 순위화한 사용자를 cold [Flow]로 반환합니다.
     *
     * PageRank는 모든 그래프 정점과 간선을 대상으로 계산한 뒤 결과를
     * User label로 필터링합니다. 점수가 높을수록 사용자가 신원 그래프에서 더 많이 연결되어 있음을 뜻합니다.
     * 발행되는 각 [SuspiciousUserScore.rank]는 1부터 시작합니다.
     *
     * @param limit 발행할 [SuspiciousUserScore] 항목의 최대 개수입니다(기본값 20, 0보다 커야 함).
     * @return 점수 내림차순으로 정렬된 cold [Flow]이며 [SuspiciousUserScore.rank]는 1부터 시작합니다.
     */
    fun rankSuspiciousUsers(limit: Int = 20): Flow<SuspiciousUserScore> {
        limit.requirePositiveNumber("limit")
        // vertexLabel = UserLabel.label은 topK = limit이 사용자 수에 직접 적용되게 합니다.
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

    /**
     * PageRank Flow를 모두 수집하고 호출별 알고리즘 실행 경로와 함께 반환합니다.
     *
     * 수집 중 coroutine이 취소되면 observer를 호출하지 않습니다. 정상 수집 뒤에도
     * observer 호출 직전에 취소 상태를 다시 확인합니다.
     *
     * @param limit 반환할 점수의 최대 개수입니다.
     * @param policy native provider 선택 정책입니다.
     */
    suspend fun rankSuspiciousUsersWithExecution(
        limit: Int = 20,
        policy: GraphAlgorithmProviderPolicy = GraphAlgorithmProviderPolicy.AUTO,
    ): SuspiciousUserRanking {
        limit.requirePositiveNumber("limit")
        val execution = GraphAlgorithmProviderSelector.select(
            algorithm = GraphAlgorithmId.PAGE_RANK,
            policy = policy,
        )
        val boundedExecution = AbuserAlgorithmExecution.from(execution)
        val scores = rankSuspiciousUsers(limit).toList()
        currentCoroutineContext().ensureActive()
        notifyExecution(execution)
        return SuspiciousUserRanking(scores, boundedExecution)
    }

    private fun notifyExecution(execution: GraphAlgorithmExecution) {
        try {
            executionObserver.onExecution(execution)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            log.warn { "Graph algorithm execution observer failed" }
        }
    }

}

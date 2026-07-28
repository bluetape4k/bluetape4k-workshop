package io.bluetape4k.workshop.graph.eventlineage.service

import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.graph.eventlineage.model.AggregateAuditTrail
import io.bluetape4k.workshop.graph.eventlineage.model.ApprovalEvidence
import io.bluetape4k.workshop.graph.eventlineage.model.LineageNode
import io.bluetape4k.workshop.graph.eventlineage.model.LineagePath
import io.bluetape4k.workshop.graph.eventlineage.schema.ActorLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.AggregateLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.ApprovedByLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.CausedByLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.DecidedByLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.DecisionLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.EmitsLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.EventLabel
import io.bluetape4k.workshop.graph.eventlineage.schema.SupersedesLabel

/**
 * 비즈니스 이벤트 lineage와 감사 재구성을 위한 블로킹 그래프 서비스입니다.
 *
 * ## 동작 / 계약
 * - 그래프 변경 또는 조회 작업 전에 [initialize]를 호출해야 합니다.
 * - 정점 변경 메서드는 도메인 키 기준으로 멱등입니다.
 * - 간선 변경 메서드는 직접 그래프 간선을 만들며 반복 호출을 중복 제거하지 않습니다.
 * - 조회 메서드는 알 수 없는 ID에는 빈 결과를 반환하고, 빈 ID에는 조기 실패합니다.
 * - 인과 순회는 `Event -CAUSED_BY-> upstream Event`를 따르며 [maxDepth]로 제한됩니다.
 *
 * ## 사용 예
 * ```kotlin
 * val service = EventLineageService(ops, "event_lineage")
 * service.initialize()
 * val order = service.addAggregate("order-1001", "Order", "APPROVED", version = 4)
 * val approved = service.addEvent("order-approved", "OrderApproved", "2026-07-02T01:04:00Z", "Approved")
 * service.emit(order.id, approved.id)
 * ```
 */
class EventLineageService(
    private val ops: GraphOperations,
    private val graphName: String,
) {
    companion object : KLogging() {
        const val MAX_TRAVERSAL_DEPTH: Int = 16
    }

    /**
     * named graph가 존재하도록 보장합니다. 여러 번 호출해도 안전합니다.
     */
    fun initialize() {
        if (!ops.graphExists(graphName)) {
            log.debug { "Creating graph: $graphName" }
            ops.createGraph(graphName)
        }
    }

    /**
     * aggregate 상태 정점을 찾거나 새로 만듭니다.
     */
    fun addAggregate(
        aggregateId: String,
        aggregateType: String,
        state: String,
        version: Int,
    ): GraphVertex {
        aggregateId.requireNotBlank("aggregateId")
        aggregateType.requireNotBlank("aggregateType")
        state.requireNotBlank("state")
        version.requireInRange(0, Int.MAX_VALUE, "version")
        return findAggregateVertex(aggregateId)
            ?: ops.createVertex(
                AggregateLabel.label,
                mapOf(
                    AggregateLabel.aggregateId.name to aggregateId,
                    AggregateLabel.aggregateType.name to aggregateType,
                    AggregateLabel.state.name to state,
                    AggregateLabel.version.name to version.toString(),
                )
            )
    }

    /**
     * event 정점을 찾거나 새로 만듭니다.
     */
    fun addEvent(
        eventId: String,
        type: String,
        occurredAt: String,
        summary: String,
    ): GraphVertex {
        eventId.requireNotBlank("eventId")
        type.requireNotBlank("type")
        occurredAt.requireNotBlank("occurredAt")
        summary.requireNotBlank("summary")
        return findEventVertex(eventId)
            ?: ops.createVertex(
                EventLabel.label,
                mapOf(
                    EventLabel.eventId.name to eventId,
                    EventLabel.type.name to type,
                    EventLabel.occurredAt.name to occurredAt,
                    EventLabel.summary.name to summary,
                )
            )
    }

    /**
     * actor 정점을 찾거나 새로 만듭니다.
     */
    fun addActor(actorId: String, displayName: String, role: String): GraphVertex {
        actorId.requireNotBlank("actorId")
        displayName.requireNotBlank("displayName")
        role.requireNotBlank("role")
        return findActorVertex(actorId)
            ?: ops.createVertex(
                ActorLabel.label,
                mapOf(
                    ActorLabel.actorId.name to actorId,
                    ActorLabel.displayName.name to displayName,
                    ActorLabel.role.name to role,
                )
            )
    }

    /**
     * decision 정점을 찾거나 새로 만듭니다.
     */
    fun addDecision(
        decisionId: String,
        decisionType: String,
        status: String,
        reason: String,
    ): GraphVertex {
        decisionId.requireNotBlank("decisionId")
        decisionType.requireNotBlank("decisionType")
        status.requireNotBlank("status")
        reason.requireNotBlank("reason")
        return findDecisionVertex(decisionId)
            ?: ops.createVertex(
                DecisionLabel.label,
                mapOf(
                    DecisionLabel.decisionId.name to decisionId,
                    DecisionLabel.decisionType.name to decisionType,
                    DecisionLabel.status.name to status,
                    DecisionLabel.reason.name to reason,
                )
            )
    }

    /**
     * Aggregate -> Event `EMITS` 간선을 만듭니다.
     */
    fun emit(aggregateVertexId: GraphElementId, eventVertexId: GraphElementId): GraphEdge =
        ops.createEdge(aggregateVertexId, eventVertexId, EmitsLabel.label, emptyMap())

    /**
     * Event -> upstream Event `CAUSED_BY` 간선을 만듭니다.
     */
    fun causedBy(eventVertexId: GraphElementId, upstreamEventVertexId: GraphElementId): GraphEdge =
        ops.createEdge(eventVertexId, upstreamEventVertexId, CausedByLabel.label, emptyMap())

    /**
     * Event -> Decision `APPROVED_BY` 간선을 만듭니다.
     */
    fun approvedBy(eventVertexId: GraphElementId, decisionVertexId: GraphElementId): GraphEdge =
        ops.createEdge(eventVertexId, decisionVertexId, ApprovedByLabel.label, emptyMap())

    /**
     * Decision -> Actor `DECIDED_BY` 간선을 만듭니다.
     */
    fun decidedBy(decisionVertexId: GraphElementId, actorVertexId: GraphElementId): GraphEdge =
        ops.createEdge(decisionVertexId, actorVertexId, DecidedByLabel.label, emptyMap())

    /**
     * Event -> previous Event `SUPERSEDES` 간선을 만듭니다.
     */
    fun supersedes(eventVertexId: GraphElementId, previousEventVertexId: GraphElementId): GraphEdge =
        ops.createEdge(eventVertexId, previousEventVertexId, SupersedesLabel.label, emptyMap())

    /**
     * 워크숍 검증과 진단을 위해 레이블별 간선 수를 셉니다.
     */
    fun edgeCount(edgeLabel: String): Long {
        edgeLabel.requireNotBlank("edgeLabel")
        return ops.findEdgesByLabel(edgeLabel).size.toLong()
    }

    /**
     * event 정점 ID에 대한 그래프 요소 조회 결과를 반환합니다.
     */
    fun findEvent(eventVertexId: GraphElementId): List<LineageNode> =
        listOfNotNull(ops.findVertexById(EventLabel.label, eventVertexId)?.toLineageNode())

    /**
     * aggregate가 발행한 이벤트를 `occurredAt`, `eventId` 순서로 정렬해 반환합니다.
     */
    fun eventsForAggregate(aggregateId: String): List<LineageNode> {
        aggregateId.requireNotBlank("aggregateId")
        val aggregate = findAggregateVertex(aggregateId) ?: return emptyList()
        return emittedEventVertices(aggregate)
            .map { it.toLineageNode() }
            .sortedByEventOrder()
    }

    /**
     * [eventId]에서 [rootEventId]까지 되짚는 제한된 인과 경로를 반환합니다.
     */
    fun causalPath(
        eventId: String,
        rootEventId: String,
        maxDepth: Int = 8,
    ): LineagePath {
        eventId.requireNotBlank("eventId")
        rootEventId.requireNotBlank("rootEventId")
        maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")

        val start = findEventVertex(eventId) ?: return LineagePath.Empty
        val root = findEventVertex(rootEventId) ?: return LineagePath.Empty
        val path = findCausalPath(start, root.id, maxDepth) ?: return LineagePath.Empty
        return LineagePath(
            nodes = path.map { it.toLineageNode() },
            edgeLabels = List(path.size - 1) { CausedByLabel.label },
        )
    }

    /**
     * aggregate의 발행 이벤트, root cause, 승인 증거를 재구성합니다.
     */
    fun auditTrailForAggregate(aggregateId: String): AggregateAuditTrail {
        aggregateId.requireNotBlank("aggregateId")
        val aggregate = findAggregateVertex(aggregateId) ?: return AggregateAuditTrail.Empty
        val eventVertices = emittedEventVertices(aggregate)
        val events = eventVertices.map { it.toLineageNode() }.sortedByEventOrder()
        val approvals = eventVertices.flatMap { event -> approvalEvidenceFor(event) }
            .sortedWith(compareBy({ it.event.properties[EventLabel.occurredAt.name]?.toString().orEmpty() }, { it.event.nodeId }))
        val rootCauses = eventVertices
            .filter { it.isRootCauseEvent() }
            .map { it.toLineageNode() }
            .sortedByEventOrder()

        return AggregateAuditTrail(
            aggregate = aggregate.toLineageNode(),
            events = events,
            approvals = approvals,
            rootCauses = rootCauses,
        )
    }

    /**
     * 최신 이벤트에서 이전 이벤트 방향으로 `SUPERSEDES` 간선을 따라갑니다.
     */
    fun supersededChain(eventId: String, maxDepth: Int = MAX_TRAVERSAL_DEPTH): List<LineageNode> {
        eventId.requireNotBlank("eventId")
        maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")
        val start = findEventVertex(eventId) ?: return emptyList()
        val chain = mutableListOf<GraphVertex>()
        val visited = mutableSetOf<GraphElementId>()
        var current: GraphVertex? = start

        while (current != null && chain.size < maxDepth && visited.add(current.id)) {
            chain += current
            val previousId = ops.findEdgesByStartId(current.id, SupersedesLabel.label)
                .firstOrNull()
                ?.endId
            current = previousId?.let { ops.findVertexById(EventLabel.label, it) }
        }

        return chain.map { it.toLineageNode() }
    }

    /**
     * 인지된 root 이벤트도 아니고 upstream cause에도 연결되지 않은 발행 이벤트를 반환합니다.
     */
    fun missingCausalLinks(aggregateId: String): List<LineageNode> {
        aggregateId.requireNotBlank("aggregateId")
        val aggregate = findAggregateVertex(aggregateId) ?: return emptyList()
        return emittedEventVertices(aggregate)
            .filterNot { event ->
                event.isRootCauseEvent() ||
                    ops.findEdgesByStartId(event.id, CausedByLabel.label).isNotEmpty() ||
                    ops.findEdgesByStartId(event.id, SupersedesLabel.label).isNotEmpty()
            }
            .map { it.toLineageNode() }
            .sortedByEventOrder()
    }

    private fun findAggregateVertex(aggregateId: String): GraphVertex? =
        ops.findVerticesByLabel(AggregateLabel.label, mapOf(AggregateLabel.aggregateId.name to aggregateId))
            .firstOrNull()

    private fun findEventVertex(eventId: String): GraphVertex? =
        ops.findVerticesByLabel(EventLabel.label, mapOf(EventLabel.eventId.name to eventId))
            .firstOrNull()

    private fun findActorVertex(actorId: String): GraphVertex? =
        ops.findVerticesByLabel(ActorLabel.label, mapOf(ActorLabel.actorId.name to actorId))
            .firstOrNull()

    private fun findDecisionVertex(decisionId: String): GraphVertex? =
        ops.findVerticesByLabel(DecisionLabel.label, mapOf(DecisionLabel.decisionId.name to decisionId))
            .firstOrNull()

    private fun emittedEventVertices(aggregate: GraphVertex): List<GraphVertex> =
        ops.findEdgesByStartId(aggregate.id, EmitsLabel.label)
            .mapNotNull { edge -> ops.findVertexById(EventLabel.label, edge.endId) }

    private fun approvalEvidenceFor(event: GraphVertex): List<ApprovalEvidence> =
        ops.findEdgesByStartId(event.id, ApprovedByLabel.label)
            .mapNotNull { approvalEdge ->
                val decision = ops.findVertexById(DecisionLabel.label, approvalEdge.endId) ?: return@mapNotNull null
                val actor = ops.findEdgesByStartId(decision.id, DecidedByLabel.label)
                    .firstNotNullOfOrNull { edge -> ops.findVertexById(ActorLabel.label, edge.endId) }
                    ?: return@mapNotNull null
                ApprovalEvidence(
                    event = event.toLineageNode(),
                    decision = decision.toLineageNode(),
                    actor = actor.toLineageNode(),
                )
            }

    private fun findCausalPath(
        current: GraphVertex,
        rootVertexId: GraphElementId,
        maxDepth: Int,
        visited: Set<GraphElementId> = emptySet(),
    ): List<GraphVertex>? {
        if (current.id == rootVertexId) return listOf(current)
        if (maxDepth == 0 || current.id in visited) return null

        val nextVisited = visited + current.id
        return ops.findEdgesByStartId(current.id, CausedByLabel.label)
            .mapNotNull { edge -> ops.findVertexById(EventLabel.label, edge.endId) }
            .sortedBy { it.eventId }
            .firstNotNullOfOrNull { upstream ->
                findCausalPath(upstream, rootVertexId, maxDepth - 1, nextVisited)
                    ?.let { listOf(current) + it }
            }
    }

    private fun GraphVertex.toLineageNode(): LineageNode =
        LineageNode(
            nodeId = domainNodeId,
            label = label,
            properties = properties,
        )

    private val GraphVertex.domainNodeId: String
        get() = when (label) {
            EventLabel.label     -> properties[EventLabel.eventId.name]?.toString().orEmpty()
            AggregateLabel.label -> properties[AggregateLabel.aggregateId.name]?.toString().orEmpty()
            ActorLabel.label     -> properties[ActorLabel.actorId.name]?.toString().orEmpty()
            DecisionLabel.label  -> properties[DecisionLabel.decisionId.name]?.toString().orEmpty()
            else                 -> id.value
        }

    private val GraphVertex.eventId: String
        get() = properties[EventLabel.eventId.name]?.toString().orEmpty()

    private fun GraphVertex.isRootCauseEvent(): Boolean =
        label == EventLabel.label && properties[EventLabel.type.name]?.toString()?.endsWith("Created") == true

    private fun List<LineageNode>.sortedByEventOrder(): List<LineageNode> =
        sortedWith(compareBy({ it.properties[EventLabel.occurredAt.name]?.toString().orEmpty() }, { it.nodeId }))
}

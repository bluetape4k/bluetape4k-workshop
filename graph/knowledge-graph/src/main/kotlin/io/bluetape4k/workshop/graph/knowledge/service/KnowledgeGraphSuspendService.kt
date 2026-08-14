package io.bluetape4k.workshop.graph.knowledge.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphPath
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PathOptions
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.graph.repository.requireEndpoint
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.graph.knowledge.schema.ConceptLabel
import io.bluetape4k.workshop.graph.knowledge.schema.DocumentLabel
import io.bluetape4k.workshop.graph.knowledge.schema.EntityLabel
import io.bluetape4k.workshop.graph.knowledge.schema.IsALabel
import io.bluetape4k.workshop.graph.knowledge.schema.MentionsLabel
import io.bluetape4k.workshop.graph.knowledge.schema.RelatedToLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take

/**
 * [KnowledgeGraphService]의 Coroutine/Flow 변형입니다.
 *
 * 모든 변경 작업은 `suspend` 함수입니다. 여러 결과를 반환하는 조회 작업은
 * backpressure를 의식해 소비할 수 있도록 `Flow<T>`를 반환합니다.
 *
 * ## 동작 / 계약
 * - 다른 메서드보다 먼저 [initialize]를 한 번 호출해야 합니다.
 * - 변경 메서드([addEntity], [addConcept], [addDocument])는 멱등이 아닙니다. 호출할 때마다 새 정점을 만듭니다.
 * - [mention]은 Document에서 Entity로 향하는 `MENTIONS` 방향성 간선을 만듭니다.
 * - [relateEntities]는 Entity 사이의 `RELATED_TO` 방향성 간선을 만듭니다.
 * - [classify]는 Entity에서 Concept으로 향하는 `IS_A` 방향성 간선을 만듭니다.
 * - 간선 변경 메서드는 정점 누락과 source/target label 불일치를 [IllegalArgumentException]으로 거부합니다.
 * - Flow 반환 메서드 자체는 suspend하지 않으며, 실제 소비 시점에 suspend됩니다.
 *
 * ## 사용 예
 * ```kotlin
 * val service = KnowledgeGraphSuspendService(ops, "knowledge_graph")
 * service.initialize()
 *
 * val paper = service.addDocument("doc-1", "Graph API Guide", "docs")
 * val kotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
 * service.mention(paper.id, kotlin.id, confidence = 95)
 * val entities = service.findMentionedEntities(paper.id).toList()
 * ```
 */
class KnowledgeGraphSuspendService(
    private val ops: GraphSuspendOperations,
    private val graphName: String = "knowledge_graph",
) {
    init {
        graphName.requireNotBlank("graphName")
    }

    companion object : KLoggingChannel() {
        /** neighbor 조회와 path 조회에 허용하는 최대 순회 깊이입니다. */
        const val MAX_TRAVERSAL_DEPTH: Int = 10
    }

    /** backing graph가 아직 없으면 생성합니다. */
    suspend fun initialize() {
        if (!ops.graphExists(graphName)) {
            ops.createGraph(graphName)
            log.info { "Knowledge graph '$graphName' created" }
        }
    }

    /** Entity 정점을 추가합니다. */
    suspend fun addEntity(entityId: String, name: String, entityType: String): GraphVertex {
        entityId.requireNotBlank("entityId")
        name.requireNotBlank("name")
        entityType.requireNotBlank("entityType")
        return ops.createVertex(
            EntityLabel.label,
            mapOf(
                EntityLabel.entityId.name to entityId,
                EntityLabel.name.name to name,
                EntityLabel.entityType.name to entityType,
            )
        )
    }

    /** Concept 정점을 추가합니다. */
    suspend fun addConcept(conceptId: String, name: String, domain: String = ""): GraphVertex {
        conceptId.requireNotBlank("conceptId")
        name.requireNotBlank("name")
        return ops.createVertex(
            ConceptLabel.label,
            mapOf(
                ConceptLabel.conceptId.name to conceptId,
                ConceptLabel.name.name to name,
                ConceptLabel.domain.name to domain,
            )
        )
    }

    /** Document 정점을 추가합니다. */
    suspend fun addDocument(documentId: String, title: String, source: String = ""): GraphVertex {
        documentId.requireNotBlank("documentId")
        title.requireNotBlank("title")
        return ops.createVertex(
            DocumentLabel.label,
            mapOf(
                DocumentLabel.documentId.name to documentId,
                DocumentLabel.title.name to title,
                DocumentLabel.source.name to source,
            )
        )
    }

    /**
     * [documentId]에서 [entityId]로 향하는 `MENTIONS` 간선을 만듭니다.
     *
     * @throws IllegalArgumentException endpoint가 없거나 Document -> Entity 관계가 아니면 발생합니다.
     */
    suspend fun mention(documentId: GraphElementId, entityId: GraphElementId, confidence: Int = 100) {
        confidence.requireInRange(0, 100, "confidence")
        ops.requireEndpoint(documentId, DocumentLabel.label, "documentId")
        ops.requireEndpoint(entityId, EntityLabel.label, "entityId")
        ops.createEdge(
            documentId,
            entityId,
            MentionsLabel.label,
            mapOf(MentionsLabel.confidence.name to confidence),
        )
    }

    /**
     * [fromEntityId]에서 [toEntityId]로 향하는 `RELATED_TO` 방향성 간선을 만듭니다.
     *
     * @throws IllegalArgumentException endpoint가 없거나 Entity -> Entity 관계가 아니면 발생합니다.
     */
    suspend fun relateEntities(
        fromEntityId: GraphElementId,
        toEntityId: GraphElementId,
        relationType: String = "related",
    ) {
        relationType.requireNotBlank("relationType")
        ops.requireEndpoint(fromEntityId, EntityLabel.label, "fromEntityId")
        ops.requireEndpoint(toEntityId, EntityLabel.label, "toEntityId")
        ops.createEdge(
            fromEntityId,
            toEntityId,
            RelatedToLabel.label,
            mapOf(RelatedToLabel.relationType.name to relationType),
        )
    }

    /**
     * [entityId]에서 [conceptId]로 향하는 `IS_A` 간선을 만듭니다.
     *
     * @throws IllegalArgumentException endpoint가 없거나 Entity -> Concept 관계가 아니면 발생합니다.
     */
    suspend fun classify(entityId: GraphElementId, conceptId: GraphElementId) {
        ops.requireEndpoint(entityId, EntityLabel.label, "entityId")
        ops.requireEndpoint(conceptId, ConceptLabel.label, "conceptId")
        ops.createEdge(entityId, conceptId, IsALabel.label, emptyMap())
    }

    /** [documentId]가 언급한 Entity를 Flow로 반환합니다. */
    fun findMentionedEntities(documentId: GraphElementId): Flow<GraphVertex> =
        ops.neighbors(
            documentId,
            NeighborOptions(edgeLabel = MentionsLabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * [entityId]에서 [depth] hop 안에 `RELATED_TO` 간선으로 도달할 수 있는 Entity를 반환합니다.
     *
     * @param depth 순회 깊이입니다. 1..[MAX_TRAVERSAL_DEPTH] 범위여야 합니다.
     */
    fun findRelatedEntities(entityId: GraphElementId, depth: Int = 1): Flow<GraphVertex> {
        depth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "depth")
        return ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = RelatedToLabel.label, direction = Direction.OUTGOING, maxDepth = depth),
        )
    }

    /** [entityId]가 속하는 Concept을 반환합니다. `IS_A` 간선을 따릅니다. */
    fun findConceptsForEntity(entityId: GraphElementId): Flow<GraphVertex> =
        ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = IsALabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * 두 Entity 사이의 관계 path를 `RELATED_TO` 간선을 따라 추론합니다.
     *
     * @param maxDepth 최대 순회 hop 수입니다. 기본값은 3입니다.
     * @param maxPaths 반환할 path 수의 상한입니다. 기본값은 10입니다.
     */
    fun inferRelationshipPaths(
        fromEntityId: GraphElementId,
        toEntityId: GraphElementId,
        maxDepth: Int = 3,
        maxPaths: Int = 10,
    ): Flow<GraphPath> {
        maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")
        maxPaths.requireInRange(1, Int.MAX_VALUE, "maxPaths")
        return ops.allPaths(
            fromEntityId,
            toEntityId,
            PathOptions(edgeLabel = RelatedToLabel.label, maxDepth = maxDepth),
        ).take(maxPaths)
    }

}

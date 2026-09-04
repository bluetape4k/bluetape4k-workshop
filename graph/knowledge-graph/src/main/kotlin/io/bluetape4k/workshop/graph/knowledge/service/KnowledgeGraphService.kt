package io.bluetape4k.workshop.graph.knowledge.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphPath
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PathOptions
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.repository.requireEndpoint
import io.bluetape4k.graph.schema.GraphSchemaPlan
import io.bluetape4k.graph.schema.GraphSchemaPlanOptions
import io.bluetape4k.graph.schema.plan
import io.bluetape4k.graph.schema.schemaManager
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.graph.knowledge.schema.ConceptLabel
import io.bluetape4k.workshop.graph.knowledge.schema.DocumentLabel
import io.bluetape4k.workshop.graph.knowledge.schema.EntityLabel
import io.bluetape4k.workshop.graph.knowledge.schema.IsALabel
import io.bluetape4k.workshop.graph.knowledge.schema.KnowledgeGraphSchema
import io.bluetape4k.workshop.graph.knowledge.schema.MentionsLabel
import io.bluetape4k.workshop.graph.knowledge.schema.RelatedToLabel

/**
 * knowledge graph 작업을 수행하는 블로킹 그래프 서비스입니다.
 *
 * Entity(사람, 장소, 기술), Concept(도메인 어휘), Document(원천 자료)를 모델링하고,
 * 문서 mention을 통한 Entity 조회, 의미 관계 순회, 제한된 path 추론을 보여줍니다.
 *
 * ## 동작 / 계약
 * - [initialize]는 기본 dry-run schema plan을 먼저 계산하고 named graph를 준비합니다.
 *   schema plan이 실패하면 graph/seed 쓰기 전에 예외를 전달합니다.
 * - [addEntity], [addConcept], [addDocument]는 멱등이 아닙니다. 호출할 때마다 새 정점을 만듭니다.
 * - [mention]은 Document에서 Entity로 향하는 `MENTIONS` 방향성 간선을 만듭니다.
 * - [relateEntities]는 한 Entity에서 다른 Entity로 향하는 `RELATED_TO` 방향성 간선을 만듭니다.
 * - [classify]는 Entity에서 Concept으로 향하는 `IS_A` 방향성 간선을 만듭니다.
 * - 간선 변경 메서드는 정점 누락과 source/target label 불일치를 [IllegalArgumentException]으로 거부합니다.
 * - [inferRelationshipPaths]는 `RELATED_TO` 간선만 따라 검색합니다.
 *
 * ## 사용 예
 * ```kotlin
 * val service = KnowledgeGraphService(ops, "knowledge_graph")
 * service.initialize()
 *
 * val paper = service.addDocument("doc-1", "Graph API Guide", "docs")
 * val kotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
 * service.mention(paper.id, kotlin.id, confidence = 95)
 * ```
 */
class KnowledgeGraphService(
    private val ops: GraphOperations,
    private val graphName: String = "knowledge_graph",
) {
    init {
        graphName.requireNotBlank("graphName")
    }

    companion object : KLogging() {
        /** neighbor 조회와 path 조회에 허용하는 최대 순회 깊이입니다. */
        const val MAX_TRAVERSAL_DEPTH: Int = 10
    }

    /**
     * schema plan을 먼저 계산한 뒤 backing graph가 아직 없으면 생성합니다.
     *
     * 반환된 plan은 자동 적용되지 않습니다. 기존 호출부는 반환 값을 무시해도 됩니다.
     */
    fun initialize(options: GraphSchemaPlanOptions = GraphSchemaPlanOptions()): GraphSchemaPlan {
        val plan = planSchema(options)
        if (!ops.graphExists(graphName)) {
            ops.createGraph(graphName)
            log.info { "Knowledge graph '$graphName' created" }
        }
        return plan
    }

    /**
     * 현재 backend schema와 knowledge graph desired schema의 차이를 계산합니다.
     * 기본값은 dry-run이며 index/constraint DDL을 실행하지 않습니다.
     */
    fun planSchema(options: GraphSchemaPlanOptions = GraphSchemaPlanOptions()): GraphSchemaPlan =
        ops.schemaManager().plan(KnowledgeGraphSchema.desiredSchema(), options)

    /**
     * 그래프에 Entity 정점을 추가합니다.
     *
     * @param entityId 안정적인 도메인 키입니다. slug나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
     * @param name 사람이 읽을 수 있는 표시 이름입니다.
     * @param entityType `"Language"`, `"Framework"`, `"Person"` 같은 자유 형식 분류입니다.
     */
    fun addEntity(entityId: String, name: String, entityType: String): GraphVertex {
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

    /**
     * 정규화된 도메인 어휘 항목을 나타내는 Concept 정점을 추가합니다.
     *
     * @param conceptId 안정적인 도메인 키입니다.
     * @param name Concept의 표시 이름입니다.
     * @param domain `"software"`, `"science"` 같은 주제 영역입니다.
     */
    fun addConcept(conceptId: String, name: String, domain: String = ""): GraphVertex {
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

    /**
     * Entity를 언급하는 원천 문서를 나타내는 Document 정점을 추가합니다.
     *
     * @param documentId 안정적인 도메인 키입니다.
     * @param title 문서 제목입니다.
     * @param source 원천 시스템 또는 URL입니다. 선택 값입니다.
     */
    fun addDocument(documentId: String, title: String, source: String = ""): GraphVertex {
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
     * @param confidence 추출 신뢰도 점수입니다. 범위는 0-100입니다.
     * @throws IllegalArgumentException endpoint가 없거나 Document -> Entity 관계가 아니면 발생합니다.
     */
    fun mention(documentId: GraphElementId, entityId: GraphElementId, confidence: Int = 100) {
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
     * @param relationType `"has-feature"`, `"integrates-with"` 같은 관계 종류를 설명합니다.
     * @throws IllegalArgumentException endpoint가 없거나 Entity -> Entity 관계가 아니면 발생합니다.
     */
    fun relateEntities(
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
     * [entityId]에서 [conceptId]로 향하는 `IS_A` 간선을 만듭니다. Entity -> Concept 분류입니다.
     *
     * @throws IllegalArgumentException endpoint가 없거나 Entity -> Concept 관계가 아니면 발생합니다.
     */
    fun classify(entityId: GraphElementId, conceptId: GraphElementId) {
        ops.requireEndpoint(entityId, EntityLabel.label, "entityId")
        ops.requireEndpoint(conceptId, ConceptLabel.label, "conceptId")
        ops.createEdge(entityId, conceptId, IsALabel.label, emptyMap())
    }

    /**
     * 지정한 Document가 언급한 Entity를 반환합니다. 바깥 방향 `MENTIONS` 간선을 따릅니다.
     */
    fun findMentionedEntities(documentId: GraphElementId): List<GraphVertex> =
        ops.neighbors(
            documentId,
            NeighborOptions(edgeLabel = MentionsLabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * [entityId]에서 [depth] hop 안에 `RELATED_TO` 간선으로 도달할 수 있는 Entity를 반환합니다.
     *
     * @param depth 순회 깊이입니다. 1..[MAX_TRAVERSAL_DEPTH] 범위여야 합니다.
     */
    fun findRelatedEntities(entityId: GraphElementId, depth: Int = 1): List<GraphVertex> {
        depth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "depth")
        return ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = RelatedToLabel.label, direction = Direction.OUTGOING, maxDepth = depth),
        )
    }

    /**
     * [entityId]가 속하는 Concept을 반환합니다. 바깥 방향 `IS_A` 간선을 따릅니다.
     */
    fun findConceptsForEntity(entityId: GraphElementId): List<GraphVertex> =
        ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = IsALabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * 두 Entity 사이의 관계 path를 `RELATED_TO` 간선을 따라 추론해 반환합니다.
     *
     * @param maxDepth 최대 순회 hop 수입니다. 기본값은 3입니다.
     * @param maxPaths 반환할 path 수의 상한입니다. 기본값은 10입니다.
     */
    fun inferRelationshipPaths(
        fromEntityId: GraphElementId,
        toEntityId: GraphElementId,
        maxDepth: Int = 3,
        maxPaths: Int = 10,
    ): List<GraphPath> {
        maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")
        maxPaths.requireInRange(1, Int.MAX_VALUE, "maxPaths")
        return ops.allPaths(
            fromEntityId,
            toEntityId,
            PathOptions(edgeLabel = RelatedToLabel.label, maxDepth = maxDepth),
        ).take(maxPaths)
    }

}

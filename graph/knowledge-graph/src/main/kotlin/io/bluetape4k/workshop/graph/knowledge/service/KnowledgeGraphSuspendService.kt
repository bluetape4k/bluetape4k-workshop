package io.bluetape4k.workshop.graph.knowledge.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphPath
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PathOptions
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.graph.knowledge.schema.ConceptLabel
import io.bluetape4k.workshop.graph.knowledge.schema.DocumentLabel
import io.bluetape4k.workshop.graph.knowledge.schema.EntityLabel
import io.bluetape4k.workshop.graph.knowledge.schema.IsALabel
import io.bluetape4k.workshop.graph.knowledge.schema.MentionsLabel
import io.bluetape4k.workshop.graph.knowledge.schema.RelatedToLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take

/**
 * Coroutine/Flow variant of [KnowledgeGraphService].
 *
 * All mutating operations are `suspend` functions. Query operations that return multiple
 * results return `Flow<T>` for backpressure-aware consumption.
 *
 * ## Behavior / Contract
 * - [initialize] must be called once before any other method.
 * - Mutators ([addEntity], [addConcept], [addDocument]) are not idempotent — each call creates a new vertex.
 * - [mention] creates a directed MENTIONS edge from Document to Entity.
 * - [relateEntities] creates a directed RELATED_TO edge between Entities.
 * - [classify] creates a directed IS_A edge from Entity to Concept.
 * - Edge mutators reject missing vertices and source/target label mismatches with [IllegalArgumentException].
 * - Flow-returning methods do not suspend; consumption suspends.
 *
 * ## Usage
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
        /** Maximum traversal depth for neighbor and path queries. */
        const val MAX_TRAVERSAL_DEPTH: Int = 10
    }

    /** Creates the backing graph when it does not already exist. */
    suspend fun initialize() {
        if (!ops.graphExists(graphName)) {
            ops.createGraph(graphName)
            log.info { "Knowledge graph '$graphName' created" }
        }
    }

    /** Adds an entity vertex. */
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

    /** Adds a concept vertex. */
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

    /** Adds a document vertex. */
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
     * Creates a MENTIONS edge from [documentId] to [entityId].
     *
     * @throws IllegalArgumentException when endpoints are missing or not Document → Entity.
     */
    suspend fun mention(documentId: GraphElementId, entityId: GraphElementId, confidence: Int = 100) {
        confidence.requireInRange(0, 100, "confidence")
        requireEndpoint(documentId, DocumentLabel.label, "documentId")
        requireEndpoint(entityId, EntityLabel.label, "entityId")
        ops.createEdge(
            documentId,
            entityId,
            MentionsLabel.label,
            mapOf(MentionsLabel.confidence.name to confidence),
        )
    }

    /**
     * Creates a directed RELATED_TO edge from [fromEntityId] to [toEntityId].
     *
     * @throws IllegalArgumentException when endpoints are missing or not Entity → Entity.
     */
    suspend fun relateEntities(
        fromEntityId: GraphElementId,
        toEntityId: GraphElementId,
        relationType: String = "related",
    ) {
        relationType.requireNotBlank("relationType")
        requireEndpoint(fromEntityId, EntityLabel.label, "fromEntityId")
        requireEndpoint(toEntityId, EntityLabel.label, "toEntityId")
        ops.createEdge(
            fromEntityId,
            toEntityId,
            RelatedToLabel.label,
            mapOf(RelatedToLabel.relationType.name to relationType),
        )
    }

    /**
     * Creates an IS_A edge from [entityId] to [conceptId].
     *
     * @throws IllegalArgumentException when endpoints are missing or not Entity → Concept.
     */
    suspend fun classify(entityId: GraphElementId, conceptId: GraphElementId) {
        requireEndpoint(entityId, EntityLabel.label, "entityId")
        requireEndpoint(conceptId, ConceptLabel.label, "conceptId")
        ops.createEdge(entityId, conceptId, IsALabel.label, emptyMap())
    }

    /** Returns entities mentioned by [documentId] as a Flow. */
    fun findMentionedEntities(documentId: GraphElementId): Flow<GraphVertex> =
        ops.neighbors(
            documentId,
            NeighborOptions(edgeLabel = MentionsLabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * Returns entities reachable from [entityId] via RELATED_TO edges within [depth] hops.
     *
     * @param depth traversal depth; must be in 1..[MAX_TRAVERSAL_DEPTH]
     */
    fun findRelatedEntities(entityId: GraphElementId, depth: Int = 1): Flow<GraphVertex> {
        depth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "depth")
        return ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = RelatedToLabel.label, direction = Direction.OUTGOING, maxDepth = depth),
        )
    }

    /** Returns concepts that [entityId] is classified under (IS_A edges). */
    fun findConceptsForEntity(entityId: GraphElementId): Flow<GraphVertex> =
        ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = IsALabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * Infers relationship paths between two entities along RELATED_TO edges.
     *
     * @param maxDepth maximum traversal hops (default 3)
     * @param maxPaths upper bound on returned paths (default 10)
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

    private suspend fun requireEndpoint(id: GraphElementId, expectedLabel: String, parameterName: String): GraphVertex {
        val vertex = ops.findVertexById(id).requireNotNull(parameterName)
        vertex.label.requireEquals(expectedLabel, "$parameterName.label")
        return vertex
    }
}

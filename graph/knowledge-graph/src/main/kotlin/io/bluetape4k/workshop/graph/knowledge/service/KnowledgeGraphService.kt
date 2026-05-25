package io.bluetape4k.workshop.graph.knowledge.service

import io.bluetape4k.graph.model.Direction
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphPath
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.model.NeighborOptions
import io.bluetape4k.graph.model.PathOptions
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.graph.knowledge.schema.ConceptLabel
import io.bluetape4k.workshop.graph.knowledge.schema.DocumentLabel
import io.bluetape4k.workshop.graph.knowledge.schema.EntityLabel
import io.bluetape4k.workshop.graph.knowledge.schema.IsALabel
import io.bluetape4k.workshop.graph.knowledge.schema.MentionsLabel
import io.bluetape4k.workshop.graph.knowledge.schema.RelatedToLabel

/**
 * Blocking graph service for knowledge graph operations.
 *
 * Models entities (people, places, technologies), concepts (domain vocabulary),
 * and documents (source materials), then demonstrates entity lookup through document
 * mentions, semantic relationship traversal, and bounded path inference.
 *
 * ## Behavior / Contract
 * - [initialize] must be called once before any other method to ensure the named graph exists.
 * - [addEntity], [addConcept], [addDocument] are not idempotent — each call creates a new vertex.
 * - [mention] creates a directed MENTIONS edge from a Document to an Entity.
 * - [relateEntities] creates a directed RELATED_TO edge from one Entity to another.
 * - [classify] creates a directed IS_A edge from an Entity to a Concept.
 * - [inferRelationshipPaths] searches only along RELATED_TO edges.
 *
 * ## Usage
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
    companion object : KLogging() {
        /** Maximum traversal depth for neighbor and path queries. */
        const val MAX_TRAVERSAL_DEPTH: Int = 10
    }

    /**
     * Creates the backing graph when it does not already exist.
     */
    fun initialize() {
        if (!ops.graphExists(graphName)) {
            ops.createGraph(graphName)
            log.info { "Knowledge graph '$graphName' created" }
        }
    }

    /**
     * Adds an entity vertex to the graph.
     *
     * @param entityId stable domain key (opaque string, e.g. slug or UUID)
     * @param name human-readable display name
     * @param entityType free-form category (e.g. "Language", "Framework", "Person")
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
     * Adds a concept vertex representing a normalized domain vocabulary term.
     *
     * @param conceptId stable domain key
     * @param name display name of the concept
     * @param domain subject area (e.g. "software", "science")
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
     * Adds a document vertex representing a source document that mentions entities.
     *
     * @param documentId stable domain key
     * @param title document title
     * @param source origin system or URL (optional)
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
     * Creates a MENTIONS edge from [documentId] to [entityId].
     *
     * @param confidence extraction confidence score 0–100
     */
    fun mention(documentId: GraphElementId, entityId: GraphElementId, confidence: Int = 100) {
        confidence.requireInRange(0, 100, "confidence")
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
     * @param relationType describes the kind of relationship (e.g. "has-feature", "integrates-with")
     */
    fun relateEntities(
        fromEntityId: GraphElementId,
        toEntityId: GraphElementId,
        relationType: String = "related",
    ) {
        relationType.requireNotBlank("relationType")
        ops.createEdge(
            fromEntityId,
            toEntityId,
            RelatedToLabel.label,
            mapOf(RelatedToLabel.relationType.name to relationType),
        )
    }

    /**
     * Creates an IS_A edge from [entityId] to [conceptId] (entity → concept classification).
     */
    fun classify(entityId: GraphElementId, conceptId: GraphElementId) {
        ops.createEdge(entityId, conceptId, IsALabel.label, emptyMap())
    }

    /**
     * Returns entities that a given document mentions (follows MENTIONS edges outward).
     */
    fun findMentionedEntities(documentId: GraphElementId): List<GraphVertex> =
        ops.neighbors(
            documentId,
            NeighborOptions(edgeLabel = MentionsLabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * Returns entities reachable from [entityId] via RELATED_TO edges within [depth] hops.
     *
     * @param depth traversal depth; must be in 1..[MAX_TRAVERSAL_DEPTH]
     */
    fun findRelatedEntities(entityId: GraphElementId, depth: Int = 1): List<GraphVertex> {
        depth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "depth")
        return ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = RelatedToLabel.label, direction = Direction.OUTGOING, maxDepth = depth),
        )
    }

    /**
     * Returns concepts that [entityId] is classified under (follows IS_A edges outward).
     */
    fun findConceptsForEntity(entityId: GraphElementId): List<GraphVertex> =
        ops.neighbors(
            entityId,
            NeighborOptions(edgeLabel = IsALabel.label, direction = Direction.OUTGOING, maxDepth = 1),
        )

    /**
     * Infers and returns relationship paths between two entities along RELATED_TO edges.
     *
     * @param maxDepth maximum traversal hops (default 3)
     * @param maxPaths upper bound on returned paths (default 10)
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

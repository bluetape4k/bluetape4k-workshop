package io.bluetape4k.workshop.graph.knowledge.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// Vertex Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Entity vertex representing a real-world object such as a person, organization, place, or product.
 *
 * ## Properties
 * - `entityId` — stable domain key (opaque string, e.g. slug or UUID)
 * - `name` — human-readable display name
 * - `entityType` — free-form category (e.g. "Language", "Framework", "Person")
 */
object EntityLabel : VertexLabel("Entity") {
    val entityId = string("entityId")
    val name = string("name")
    val entityType = string("entityType")
}

/**
 * Concept vertex representing a normalized domain vocabulary term.
 *
 * ## Properties
 * - `conceptId` — stable domain key
 * - `name` — display name of the concept
 * - `domain` — subject area (e.g. "software", "science")
 */
object ConceptLabel : VertexLabel("Concept") {
    val conceptId = string("conceptId")
    val name = string("name")
    val domain = string("domain")
}

/**
 * Document vertex representing a source document that mentions entities.
 *
 * ## Properties
 * - `documentId` — stable domain key
 * - `title` — document title
 * - `source` — origin system or URL (optional)
 */
object DocumentLabel : VertexLabel("Document") {
    val documentId = string("documentId")
    val title = string("title")
    val source = string("source")
}

// ────────────────────────────────────────────────────────────────────────────
// Edge Labels
// ────────────────────────────────────────────────────────────────────────────

/**
 * Edge from a Document to an Entity indicating the document mentions the entity.
 *
 * ## Properties
 * - `confidence` — extraction confidence score 0–100
 */
object MentionsLabel : EdgeLabel("MENTIONS", DocumentLabel, EntityLabel) {
    val confidence = integer("confidence")
}

/**
 * Directed edge between two Entity vertices indicating a semantic relationship.
 *
 * ## Properties
 * - `relationType` — describes the kind of relationship (e.g. "has-feature", "integrates-with")
 */
object RelatedToLabel : EdgeLabel("RELATED_TO", EntityLabel, EntityLabel) {
    val relationType = string("relationType")
}

/**
 * Edge from an Entity to a Concept indicating that the entity is classified under that concept.
 *
 * Direction: Entity → Concept (IS_A classification).
 */
object IsALabel : EdgeLabel("IS_A", EntityLabel, ConceptLabel)

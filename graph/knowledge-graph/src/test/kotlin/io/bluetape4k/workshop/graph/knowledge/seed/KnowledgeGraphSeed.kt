package io.bluetape4k.workshop.graph.knowledge.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphService
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphSuspendService
import java.io.Serializable

/**
 * Snapshot of all vertices created by [seedKnowledgeGraph].
 *
 * Graph structure (Technology knowledge graph):
 * ```
 * Documents:
 *   doc-kotlin-guide  ──MENTIONS──► entity-kotlin   (confidence=95)
 *   doc-kotlin-guide  ──MENTIONS──► entity-jvm       (confidence=80)
 *   doc-spring-guide  ──MENTIONS──► entity-spring    (confidence=98)
 *   doc-spring-guide  ──MENTIONS──► entity-kotlin    (confidence=75)
 *
 * Entity relationships (RELATED_TO):
 *   entity-kotlin     ──has-feature──► entity-coroutines
 *   entity-coroutines ──integrates-with──► entity-spring
 *   entity-spring     ──runs-on──► entity-jvm
 *   entity-kotlin     ──runs-on──► entity-jvm
 *
 * Concepts (IS_A):
 *   entity-kotlin     ──IS_A──► concept-language
 *   entity-spring     ──IS_A──► concept-framework
 *   entity-coroutines ──IS_A──► concept-library
 *   entity-jvm        ──IS_A──► concept-platform
 * ```
 *
 * Key traversal expectations:
 * - doc-kotlin-guide mentions: [entity-kotlin, entity-jvm]
 * - doc-spring-guide mentions: [entity-spring, entity-kotlin]
 * - entity-kotlin related (depth=1): [entity-coroutines, entity-jvm]
 * - entity-kotlin related (depth=2): [entity-coroutines, entity-jvm, entity-spring]
 * - paths from entity-kotlin to entity-spring via RELATED_TO: kotlin→coroutines→spring
 * - entity-kotlin concepts: [concept-language]
 */
data class KnowledgeGraphSeed(
    // Documents
    val docKotlinGuide: GraphVertex,
    val docSpringGuide: GraphVertex,
    // Entities
    val entityKotlin: GraphVertex,
    val entityCoroutines: GraphVertex,
    val entitySpring: GraphVertex,
    val entityJvm: GraphVertex,
    // Concepts
    val conceptLanguage: GraphVertex,
    val conceptFramework: GraphVertex,
    val conceptLibrary: GraphVertex,
    val conceptPlatform: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Blocking seed
// ─────────────────────────────────────────────────────────────────────────

/**
 * Populates the knowledge graph with a deterministic technology-domain dataset using [service].
 *
 * See [KnowledgeGraphSeed] for the full graph topology and traversal expectations.
 */
fun seedKnowledgeGraph(service: KnowledgeGraphService): KnowledgeGraphSeed {
    // Documents
    val docKotlinGuide = service.addDocument("doc-kotlin-guide", "Kotlin in Action", "book")
    val docSpringGuide = service.addDocument("doc-spring-guide", "Spring Boot Reference", "docs")

    // Entities
    val entityKotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
    val entityCoroutines = service.addEntity("entity-coroutines", "Coroutines", "Library")
    val entitySpring = service.addEntity("entity-spring", "Spring", "Framework")
    val entityJvm = service.addEntity("entity-jvm", "JVM", "Platform")

    // Concepts
    val conceptLanguage = service.addConcept("concept-language", "Programming Language", "software")
    val conceptFramework = service.addConcept("concept-framework", "Application Framework", "software")
    val conceptLibrary = service.addConcept("concept-library", "Software Library", "software")
    val conceptPlatform = service.addConcept("concept-platform", "Runtime Platform", "infrastructure")

    // MENTIONS edges
    service.mention(docKotlinGuide.id, entityKotlin.id, confidence = 95)
    service.mention(docKotlinGuide.id, entityJvm.id, confidence = 80)
    service.mention(docSpringGuide.id, entitySpring.id, confidence = 98)
    service.mention(docSpringGuide.id, entityKotlin.id, confidence = 75)

    // RELATED_TO edges
    service.relateEntities(entityKotlin.id, entityCoroutines.id, relationType = "has-feature")
    service.relateEntities(entityCoroutines.id, entitySpring.id, relationType = "integrates-with")
    service.relateEntities(entitySpring.id, entityJvm.id, relationType = "runs-on")
    service.relateEntities(entityKotlin.id, entityJvm.id, relationType = "runs-on")

    // IS_A edges (classification)
    service.classify(entityKotlin.id, conceptLanguage.id)
    service.classify(entitySpring.id, conceptFramework.id)
    service.classify(entityCoroutines.id, conceptLibrary.id)
    service.classify(entityJvm.id, conceptPlatform.id)

    return KnowledgeGraphSeed(
        docKotlinGuide = docKotlinGuide,
        docSpringGuide = docSpringGuide,
        entityKotlin = entityKotlin,
        entityCoroutines = entityCoroutines,
        entitySpring = entitySpring,
        entityJvm = entityJvm,
        conceptLanguage = conceptLanguage,
        conceptFramework = conceptFramework,
        conceptLibrary = conceptLibrary,
        conceptPlatform = conceptPlatform,
    )
}

// ─────────────────────────────────────────────────────────────────────────
// Suspend seed
// ─────────────────────────────────────────────────────────────────────────

/**
 * Suspend variant of [seedKnowledgeGraph].
 */
suspend fun seedKnowledgeGraph(service: KnowledgeGraphSuspendService): KnowledgeGraphSeed {
    // Documents
    val docKotlinGuide = service.addDocument("doc-kotlin-guide", "Kotlin in Action", "book")
    val docSpringGuide = service.addDocument("doc-spring-guide", "Spring Boot Reference", "docs")

    // Entities
    val entityKotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
    val entityCoroutines = service.addEntity("entity-coroutines", "Coroutines", "Library")
    val entitySpring = service.addEntity("entity-spring", "Spring", "Framework")
    val entityJvm = service.addEntity("entity-jvm", "JVM", "Platform")

    // Concepts
    val conceptLanguage = service.addConcept("concept-language", "Programming Language", "software")
    val conceptFramework = service.addConcept("concept-framework", "Application Framework", "software")
    val conceptLibrary = service.addConcept("concept-library", "Software Library", "software")
    val conceptPlatform = service.addConcept("concept-platform", "Runtime Platform", "infrastructure")

    // MENTIONS edges
    service.mention(docKotlinGuide.id, entityKotlin.id, confidence = 95)
    service.mention(docKotlinGuide.id, entityJvm.id, confidence = 80)
    service.mention(docSpringGuide.id, entitySpring.id, confidence = 98)
    service.mention(docSpringGuide.id, entityKotlin.id, confidence = 75)

    // RELATED_TO edges
    service.relateEntities(entityKotlin.id, entityCoroutines.id, relationType = "has-feature")
    service.relateEntities(entityCoroutines.id, entitySpring.id, relationType = "integrates-with")
    service.relateEntities(entitySpring.id, entityJvm.id, relationType = "runs-on")
    service.relateEntities(entityKotlin.id, entityJvm.id, relationType = "runs-on")

    // IS_A edges (classification)
    service.classify(entityKotlin.id, conceptLanguage.id)
    service.classify(entitySpring.id, conceptFramework.id)
    service.classify(entityCoroutines.id, conceptLibrary.id)
    service.classify(entityJvm.id, conceptPlatform.id)

    return KnowledgeGraphSeed(
        docKotlinGuide = docKotlinGuide,
        docSpringGuide = docSpringGuide,
        entityKotlin = entityKotlin,
        entityCoroutines = entityCoroutines,
        entitySpring = entitySpring,
        entityJvm = entityJvm,
        conceptLanguage = conceptLanguage,
        conceptFramework = conceptFramework,
        conceptLibrary = conceptLibrary,
        conceptPlatform = conceptPlatform,
    )
}

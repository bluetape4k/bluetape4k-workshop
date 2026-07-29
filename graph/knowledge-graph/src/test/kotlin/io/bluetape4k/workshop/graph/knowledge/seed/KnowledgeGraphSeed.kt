package io.bluetape4k.workshop.graph.knowledge.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphService
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphSuspendService
import java.io.Serializable

/**
 * [seedKnowledgeGraph]가 생성한 모든 정점의 스냅샷입니다.
 *
 * 그래프 구조(기술 knowledge graph):
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
 * 주요 순회 기대값:
 * - doc-kotlin-guide mentions: [entity-kotlin, entity-jvm]
 * - doc-spring-guide mentions: [entity-spring, entity-kotlin]
 * - entity-kotlin related(depth=1): [entity-coroutines, entity-jvm]
 * - entity-kotlin related(depth=2): [entity-coroutines, entity-jvm, entity-spring]
 * - RELATED_TO를 통한 entity-kotlin에서 entity-spring까지의 path: kotlin -> coroutines -> spring
 * - entity-kotlin concepts: [concept-language]
 */
data class KnowledgeGraphSeed(
    // Document 정점
    val docKotlinGuide: GraphVertex,
    val docSpringGuide: GraphVertex,
    // Entity 정점
    val entityKotlin: GraphVertex,
    val entityCoroutines: GraphVertex,
    val entitySpring: GraphVertex,
    val entityJvm: GraphVertex,
    // Concept 정점
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
// 블로킹 seed
// ─────────────────────────────────────────────────────────────────────────

/**
 * [service]를 사용해 결정적인 기술 도메인 dataset으로 knowledge graph를 채웁니다.
 *
 * 전체 그래프 토폴로지와 순회 기대값은 [KnowledgeGraphSeed]를 참고합니다.
 */
fun seedKnowledgeGraph(service: KnowledgeGraphService): KnowledgeGraphSeed {
    // Document 정점
    val docKotlinGuide = service.addDocument("doc-kotlin-guide", "Kotlin in Action", "book")
    val docSpringGuide = service.addDocument("doc-spring-guide", "Spring Boot Reference", "docs")

    // Entity 정점
    val entityKotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
    val entityCoroutines = service.addEntity("entity-coroutines", "Coroutines", "Library")
    val entitySpring = service.addEntity("entity-spring", "Spring", "Framework")
    val entityJvm = service.addEntity("entity-jvm", "JVM", "Platform")

    // Concept 정점
    val conceptLanguage = service.addConcept("concept-language", "Programming Language", "software")
    val conceptFramework = service.addConcept("concept-framework", "Application Framework", "software")
    val conceptLibrary = service.addConcept("concept-library", "Software Library", "software")
    val conceptPlatform = service.addConcept("concept-platform", "Runtime Platform", "infrastructure")

    // MENTIONS 간선
    service.mention(docKotlinGuide.id, entityKotlin.id, confidence = 95)
    service.mention(docKotlinGuide.id, entityJvm.id, confidence = 80)
    service.mention(docSpringGuide.id, entitySpring.id, confidence = 98)
    service.mention(docSpringGuide.id, entityKotlin.id, confidence = 75)

    // RELATED_TO 간선
    service.relateEntities(entityKotlin.id, entityCoroutines.id, relationType = "has-feature")
    service.relateEntities(entityCoroutines.id, entitySpring.id, relationType = "integrates-with")
    service.relateEntities(entitySpring.id, entityJvm.id, relationType = "runs-on")
    service.relateEntities(entityKotlin.id, entityJvm.id, relationType = "runs-on")

    // IS_A 간선(분류)
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
 * [seedKnowledgeGraph]의 suspend 변형입니다.
 */
suspend fun seedKnowledgeGraph(service: KnowledgeGraphSuspendService): KnowledgeGraphSeed {
    // Document 정점
    val docKotlinGuide = service.addDocument("doc-kotlin-guide", "Kotlin in Action", "book")
    val docSpringGuide = service.addDocument("doc-spring-guide", "Spring Boot Reference", "docs")

    // Entity 정점
    val entityKotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
    val entityCoroutines = service.addEntity("entity-coroutines", "Coroutines", "Library")
    val entitySpring = service.addEntity("entity-spring", "Spring", "Framework")
    val entityJvm = service.addEntity("entity-jvm", "JVM", "Platform")

    // Concept 정점
    val conceptLanguage = service.addConcept("concept-language", "Programming Language", "software")
    val conceptFramework = service.addConcept("concept-framework", "Application Framework", "software")
    val conceptLibrary = service.addConcept("concept-library", "Software Library", "software")
    val conceptPlatform = service.addConcept("concept-platform", "Runtime Platform", "infrastructure")

    // MENTIONS 간선
    service.mention(docKotlinGuide.id, entityKotlin.id, confidence = 95)
    service.mention(docKotlinGuide.id, entityJvm.id, confidence = 80)
    service.mention(docSpringGuide.id, entitySpring.id, confidence = 98)
    service.mention(docSpringGuide.id, entityKotlin.id, confidence = 75)

    // RELATED_TO 간선
    service.relateEntities(entityKotlin.id, entityCoroutines.id, relationType = "has-feature")
    service.relateEntities(entityCoroutines.id, entitySpring.id, relationType = "integrates-with")
    service.relateEntities(entitySpring.id, entityJvm.id, relationType = "runs-on")
    service.relateEntities(entityKotlin.id, entityJvm.id, relationType = "runs-on")

    // IS_A 간선(분류)
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

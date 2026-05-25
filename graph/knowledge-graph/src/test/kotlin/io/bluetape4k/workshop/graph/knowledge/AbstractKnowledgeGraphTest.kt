package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.knowledge.seed.KnowledgeGraphSeed
import io.bluetape4k.workshop.graph.knowledge.seed.seedKnowledgeGraph
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Abstract test suite for [KnowledgeGraphService].
 *
 * Concrete subclasses supply [ops] backed by a specific graph backend.
 * Each test runs against a clean graph — [cleanGraph] drops and re-initializes before every test.
 *
 * ## Seed topology
 * ```
 * doc-kotlin-guide ──MENTIONS──► entity-kotlin   (confidence=95)
 * doc-kotlin-guide ──MENTIONS──► entity-jvm       (confidence=80)
 * doc-spring-guide ──MENTIONS──► entity-spring    (confidence=98)
 * doc-spring-guide ──MENTIONS──► entity-kotlin    (confidence=75)
 *
 * entity-kotlin     ──has-feature──►    entity-coroutines
 * entity-coroutines ──integrates-with──► entity-spring
 * entity-spring     ──runs-on──►        entity-jvm
 * entity-kotlin     ──runs-on──►        entity-jvm
 *
 * entity-kotlin     ──IS_A──► concept-language
 * entity-spring     ──IS_A──► concept-framework
 * entity-coroutines ──IS_A──► concept-library
 * entity-jvm        ──IS_A──► concept-platform
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractKnowledgeGraphTest {

    companion object : KLogging()

    protected abstract val graphName: String
    protected abstract val ops: GraphOperations

    protected val service: KnowledgeGraphService by lazy { KnowledgeGraphService(ops, graphName) }
    protected lateinit var seed: KnowledgeGraphSeed

    @BeforeEach
    fun cleanGraph() {
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedKnowledgeGraph(service)
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENTIONS traversal
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findMentionedEntities returns entities mentioned by a document`() {
        val entityIds = service.findMentionedEntities(seed.docKotlinGuide.id)
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-kotlin"
        entityIds shouldContain "entity-jvm"
    }

    @Test
    fun `findMentionedEntities for spring guide returns spring and kotlin`() {
        val entityIds = service.findMentionedEntities(seed.docSpringGuide.id)
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-spring"
        entityIds shouldContain "entity-kotlin"
    }

    // ─────────────────────────────────────────────────────────────────────
    // RELATED_TO traversal
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findRelatedEntities returns direct neighbours at depth 1`() {
        val entityIds = service.findRelatedEntities(seed.entityKotlin.id, depth = 1)
            .map { it.properties["entityId"] }

        entityIds.shouldNotBeEmpty()
        entityIds shouldContain "entity-coroutines"
        entityIds shouldContain "entity-jvm"
    }

    @Test
    fun `findRelatedEntities at depth 2 includes transitively reachable entities`() {
        val entityIds = service.findRelatedEntities(seed.entityKotlin.id, depth = 2)
            .map { it.properties["entityId"] }

        entityIds.shouldNotBeEmpty()
        entityIds shouldContain "entity-coroutines"
        entityIds shouldContain "entity-jvm"
        entityIds shouldContain "entity-spring"
    }

    // ─────────────────────────────────────────────────────────────────────
    // IS_A classification
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findConceptsForEntity returns concept for kotlin entity`() {
        val conceptIds = service.findConceptsForEntity(seed.entityKotlin.id)
            .map { it.properties["conceptId"] }

        conceptIds.shouldNotBeEmpty()
        conceptIds shouldContain "concept-language"
    }

    @Test
    fun `findConceptsForEntity returns framework concept for spring entity`() {
        val conceptIds = service.findConceptsForEntity(seed.entitySpring.id)
            .map { it.properties["conceptId"] }

        conceptIds.shouldNotBeEmpty()
        conceptIds shouldContain "concept-framework"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Path inference
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `inferRelationshipPaths finds path from kotlin to spring`() {
        val paths = service.inferRelationshipPaths(
            seed.entityKotlin.id,
            seed.entitySpring.id,
            maxDepth = 3,
            maxPaths = 5,
        )

        paths.shouldNotBeEmpty()
        val vertexIds = paths.first().vertices.map { it.properties["entityId"] }
        vertexIds shouldContain "entity-kotlin"
        vertexIds shouldContain "entity-spring"
    }

    @Test
    fun `inferRelationshipPaths respects maxPaths bound`() {
        val paths = service.inferRelationshipPaths(
            seed.entityKotlin.id,
            seed.entityJvm.id,
            maxDepth = 4,
            maxPaths = 1,
        )

        // at most 1 path returned
        paths.size shouldBeEqualTo 1
    }

    // ─────────────────────────────────────────────────────────────────────
    // Ad-hoc entity/concept/document creation
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addEntity and retrieve via findRelatedEntities`() {
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.relateEntities(seed.entityKotlin.id, gradle.id, relationType = "uses")

        val entityIds = service.findRelatedEntities(seed.entityKotlin.id)
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-gradle"
    }

    @Test
    fun `classify and findConceptsForEntity round-trip`() {
        val buildTool = service.addConcept("concept-build-tool", "Build Tool", "infrastructure")
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.classify(gradle.id, buildTool.id)

        val conceptIds = service.findConceptsForEntity(gradle.id)
            .map { it.properties["conceptId"] }

        conceptIds shouldContain "concept-build-tool"
    }

    @Test
    fun `mention creates findMentionedEntities link`() {
        val doc = service.addDocument("doc-gradle-guide", "Gradle User Manual", "docs")
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.mention(doc.id, gradle.id, confidence = 90)

        val entityIds = service.findMentionedEntities(doc.id)
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-gradle"
    }
}

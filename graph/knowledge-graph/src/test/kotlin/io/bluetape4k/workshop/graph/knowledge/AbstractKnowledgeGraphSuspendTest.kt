package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.knowledge.seed.KnowledgeGraphSeed
import io.bluetape4k.workshop.graph.knowledge.seed.seedKnowledgeGraph
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphSuspendService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Abstract suspend test suite for [KnowledgeGraphSuspendService].
 *
 * Concrete subclasses supply [ops] backed by a specific graph backend.
 * See [AbstractKnowledgeGraphTest] for the seed topology.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractKnowledgeGraphSuspendTest {

    companion object : KLogging()

    protected abstract val graphName: String
    protected abstract val ops: GraphSuspendOperations

    protected val service: KnowledgeGraphSuspendService by lazy {
        KnowledgeGraphSuspendService(ops, graphName)
    }
    protected lateinit var seed: KnowledgeGraphSeed

    @BeforeEach
    fun cleanGraph() = runTest {
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedKnowledgeGraph(service)
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENTIONS traversal
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findMentionedEntities returns entities mentioned by a document`() = runTest {
        val entityIds = service.findMentionedEntities(seed.docKotlinGuide.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-kotlin"
        entityIds shouldContain "entity-jvm"
    }

    @Test
    fun `findMentionedEntities for spring guide returns spring and kotlin`() = runTest {
        val entityIds = service.findMentionedEntities(seed.docSpringGuide.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-spring"
        entityIds shouldContain "entity-kotlin"
    }

    // ─────────────────────────────────────────────────────────────────────
    // RELATED_TO traversal
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findRelatedEntities returns direct neighbours at depth 1`() = runTest {
        val entityIds = service.findRelatedEntities(seed.entityKotlin.id, depth = 1).toList()
            .map { it.properties["entityId"] }

        entityIds.shouldNotBeEmpty()
        entityIds shouldContain "entity-coroutines"
        entityIds shouldContain "entity-jvm"
    }

    @Test
    fun `findRelatedEntities at depth 2 includes transitively reachable entities`() = runTest {
        val entityIds = service.findRelatedEntities(seed.entityKotlin.id, depth = 2).toList()
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
    fun `findConceptsForEntity returns concept for kotlin entity`() = runTest {
        val conceptIds = service.findConceptsForEntity(seed.entityKotlin.id).toList()
            .map { it.properties["conceptId"] }

        conceptIds.shouldNotBeEmpty()
        conceptIds shouldContain "concept-language"
    }

    @Test
    fun `findConceptsForEntity returns framework concept for spring entity`() = runTest {
        val conceptIds = service.findConceptsForEntity(seed.entitySpring.id).toList()
            .map { it.properties["conceptId"] }

        conceptIds.shouldNotBeEmpty()
        conceptIds shouldContain "concept-framework"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Path inference
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `inferRelationshipPaths finds path from kotlin to spring`() = runTest {
        val paths = service.inferRelationshipPaths(
            seed.entityKotlin.id,
            seed.entitySpring.id,
            maxDepth = 3,
            maxPaths = 5,
        ).toList()

        paths.shouldNotBeEmpty()
        val vertexIds = paths.first().vertices.map { it.properties["entityId"] }
        vertexIds shouldContain "entity-kotlin"
        vertexIds shouldContain "entity-spring"
    }

    @Test
    fun `inferRelationshipPaths respects maxPaths bound`() = runTest {
        val paths = service.inferRelationshipPaths(
            seed.entityKotlin.id,
            seed.entityJvm.id,
            maxDepth = 4,
            maxPaths = 1,
        ).toList()

        paths.size shouldBeEqualTo 1
    }

    // ─────────────────────────────────────────────────────────────────────
    // Round-trip
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `classify and findConceptsForEntity round-trip`() = runTest {
        val buildTool = service.addConcept("concept-build-tool", "Build Tool", "infrastructure")
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.classify(gradle.id, buildTool.id)

        val conceptIds = service.findConceptsForEntity(gradle.id).toList()
            .map { it.properties["conceptId"] }

        conceptIds shouldContain "concept-build-tool"
    }

    @Test
    fun `mention creates findMentionedEntities link`() = runTest {
        val doc = service.addDocument("doc-gradle-guide", "Gradle User Manual", "docs")
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.mention(doc.id, gradle.id, confidence = 90)

        val entityIds = service.findMentionedEntities(doc.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-gradle"
    }
}

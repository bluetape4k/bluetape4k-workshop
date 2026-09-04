package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.schema.schemaManager
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.knowledge.seed.KnowledgeGraphSeed
import io.bluetape4k.workshop.graph.knowledge.seed.seedKnowledgeGraph
import io.bluetape4k.workshop.graph.knowledge.schema.KnowledgeGraphSchema
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [KnowledgeGraphService]용 추상 테스트 suite입니다.
 *
 * 구체 하위 클래스는 특정 그래프 backend가 뒷받침하는 [ops]를 제공합니다.
 * 각 테스트는 깨끗한 그래프에서 실행됩니다. [cleanGraph]가 매 테스트 전에 그래프를 drop하고 다시 초기화합니다.
 *
 * ## Seed 토폴로지
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
        // bluetape4k-graph 0.6.0 requires selecting the logical graph before dropping it.
        ops.createGraph(graphName)
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedKnowledgeGraph(service)
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENTIONS 순회
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
    // RELATED_TO 순회
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
    // IS_A 분류
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
    // Path 추론
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

        // 최대 path 1개만 반환합니다.
        paths.size shouldBeEqualTo 1
    }

    // ─────────────────────────────────────────────────────────────────────
    // 임시 Entity/Concept/Document 생성
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

    // ─────────────────────────────────────────────────────────────────────
    // 입력 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addEntity with blank entityId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.addEntity("", "Name", "Type")
        }
    }

    @Test
    fun `addConcept with blank conceptId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.addConcept("", "Name")
        }
    }

    @Test
    fun `addDocument with blank documentId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.addDocument("", "Title")
        }
    }

    @Test
    fun `service rejects blank graphName`() {
        assertFailsWith<IllegalArgumentException> {
            KnowledgeGraphService(ops, "")
        }
    }

    @Test
    fun `mention with confidence above 100 throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.mention(seed.docKotlinGuide.id, seed.entityKotlin.id, confidence = 101)
        }
    }

    @Test
    fun `mention with negative confidence throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.mention(seed.docKotlinGuide.id, seed.entityKotlin.id, confidence = -1)
        }
    }

    @Test
    fun `mention rejects non-document source endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            service.mention(seed.entityKotlin.id, seed.entityJvm.id, confidence = 90)
        }
    }

    @Test
    fun `mention rejects missing document endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            service.mention(GraphElementId.of("99999999"), seed.entityKotlin.id, confidence = 90)
        }
    }

    @Test
    fun `relateEntities rejects non-entity endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            service.relateEntities(seed.docKotlinGuide.id, seed.entityJvm.id)
        }
    }

    @Test
    fun `classify rejects non-concept target endpoint`() {
        assertFailsWith<IllegalArgumentException> {
            service.classify(seed.entityKotlin.id, seed.entityJvm.id)
        }
    }

    @Test
    fun `findRelatedEntities with zero depth throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.findRelatedEntities(seed.entityKotlin.id, depth = 0)
        }
    }

    @Test
    fun `inferRelationshipPaths with zero maxDepth throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.inferRelationshipPaths(seed.entityKotlin.id, seed.entitySpring.id, maxDepth = 0)
        }
    }

    @Test
    fun `inferRelationshipPaths with zero maxPaths throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.inferRelationshipPaths(seed.entityKotlin.id, seed.entitySpring.id, maxPaths = 0)
        }
    }

    @Test
    fun `planSchema is deterministic and does not mutate live schema`() {
        val manager = ops.schemaManager()
        val indexesBefore = manager.listIndexes()
        val constraintsBefore = manager.listConstraints()

        val first = service.planSchema()
        val second = service.planSchema()

        first shouldBeEqualTo second
        first.options.dryRun shouldBeEqualTo true
        KnowledgeGraphSchema.desiredSchema().indexes.size shouldBeEqualTo 3
        KnowledgeGraphSchema.desiredSchema().constraints.size shouldBeEqualTo 3
        manager.listIndexes() shouldBeEqualTo indexesBefore
        manager.listConstraints() shouldBeEqualTo constraintsBefore
    }
}

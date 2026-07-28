package io.bluetape4k.workshop.graph.knowledge

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.graph.knowledge.seed.KnowledgeGraphSeed
import io.bluetape4k.workshop.graph.knowledge.seed.seedKnowledgeGraph
import io.bluetape4k.workshop.graph.knowledge.service.KnowledgeGraphSuspendService
import kotlinx.coroutines.flow.toList
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [KnowledgeGraphSuspendService]용 추상 suspend 테스트 suite입니다.
 *
 * 구체 하위 클래스는 특정 그래프 backend가 뒷받침하는 [ops]를 제공합니다.
 * seed 토폴로지는 [AbstractKnowledgeGraphTest]를 참고합니다.
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
    fun cleanGraph() = runSuspendIO {
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedKnowledgeGraph(service)
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENTIONS 순회
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findMentionedEntities returns entities mentioned by a document`() = runSuspendIO {
        val entityIds = service.findMentionedEntities(seed.docKotlinGuide.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-kotlin"
        entityIds shouldContain "entity-jvm"
    }

    @Test
    fun `findMentionedEntities for spring guide returns spring and kotlin`() = runSuspendIO {
        val entityIds = service.findMentionedEntities(seed.docSpringGuide.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-spring"
        entityIds shouldContain "entity-kotlin"
    }

    // ─────────────────────────────────────────────────────────────────────
    // RELATED_TO 순회
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findRelatedEntities returns direct neighbours at depth 1`() = runSuspendIO {
        val entityIds = service.findRelatedEntities(seed.entityKotlin.id, depth = 1).toList()
            .map { it.properties["entityId"] }

        entityIds.shouldNotBeEmpty()
        entityIds shouldContain "entity-coroutines"
        entityIds shouldContain "entity-jvm"
    }

    @Test
    fun `findRelatedEntities at depth 2 includes transitively reachable entities`() = runSuspendIO {
        val entityIds = service.findRelatedEntities(seed.entityKotlin.id, depth = 2).toList()
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
    fun `findConceptsForEntity returns concept for kotlin entity`() = runSuspendIO {
        val conceptIds = service.findConceptsForEntity(seed.entityKotlin.id).toList()
            .map { it.properties["conceptId"] }

        conceptIds.shouldNotBeEmpty()
        conceptIds shouldContain "concept-language"
    }

    @Test
    fun `findConceptsForEntity returns framework concept for spring entity`() = runSuspendIO {
        val conceptIds = service.findConceptsForEntity(seed.entitySpring.id).toList()
            .map { it.properties["conceptId"] }

        conceptIds.shouldNotBeEmpty()
        conceptIds shouldContain "concept-framework"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Path 추론
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `inferRelationshipPaths finds path from kotlin to spring`() = runSuspendIO {
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
    fun `inferRelationshipPaths respects maxPaths bound`() = runSuspendIO {
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
    fun `classify and findConceptsForEntity round-trip`() = runSuspendIO {
        val buildTool = service.addConcept("concept-build-tool", "Build Tool", "infrastructure")
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.classify(gradle.id, buildTool.id)

        val conceptIds = service.findConceptsForEntity(gradle.id).toList()
            .map { it.properties["conceptId"] }

        conceptIds shouldContain "concept-build-tool"
    }

    @Test
    fun `mention creates findMentionedEntities link`() = runSuspendIO {
        val doc = service.addDocument("doc-gradle-guide", "Gradle User Manual", "docs")
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.mention(doc.id, gradle.id, confidence = 90)

        val entityIds = service.findMentionedEntities(doc.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-gradle"
    }

    // ─────────────────────────────────────────────────────────────────────
    // Round-trip(H-4 수정)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addEntity and retrieve via findRelatedEntities`() = runSuspendIO {
        val gradle = service.addEntity("entity-gradle", "Gradle", "BuildTool")
        service.relateEntities(seed.entityKotlin.id, gradle.id, relationType = "uses")

        val entityIds = service.findRelatedEntities(seed.entityKotlin.id).toList()
            .map { it.properties["entityId"] }

        entityIds shouldContain "entity-gradle"
    }

    // ─────────────────────────────────────────────────────────────────────
    // 입력 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addEntity with blank entityId throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.addEntity("", "Name", "Type")
        }
    }

    @Test
    fun `addConcept with blank conceptId throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.addConcept("", "Name")
        }
    }

    @Test
    fun `addDocument with blank documentId throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.addDocument("", "Title")
        }
    }

    @Test
    fun `service rejects blank graphName`() {
        assertFailsWith<IllegalArgumentException> {
            KnowledgeGraphSuspendService(ops, "")
        }
    }

    @Test
    fun `mention with confidence above 100 throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.mention(seed.docKotlinGuide.id, seed.entityKotlin.id, confidence = 101)
        }
    }

    @Test
    fun `mention with negative confidence throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.mention(seed.docKotlinGuide.id, seed.entityKotlin.id, confidence = -1)
        }
    }

    @Test
    fun `mention rejects non-document source endpoint`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.mention(seed.entityKotlin.id, seed.entityJvm.id, confidence = 90)
        }
    }

    @Test
    fun `relateEntities rejects non-entity endpoint`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.relateEntities(seed.docKotlinGuide.id, seed.entityJvm.id)
        }
    }

    @Test
    fun `classify rejects non-concept target endpoint`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.classify(seed.entityKotlin.id, seed.entityJvm.id)
        }
    }

    @Test
    fun `findRelatedEntities with zero depth throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.findRelatedEntities(seed.entityKotlin.id, depth = 0)
        }
    }

    @Test
    fun `inferRelationshipPaths with zero maxDepth throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.inferRelationshipPaths(seed.entityKotlin.id, seed.entitySpring.id, maxDepth = 0)
        }
    }

    @Test
    fun `inferRelationshipPaths with zero maxPaths throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.inferRelationshipPaths(seed.entityKotlin.id, seed.entitySpring.id, maxPaths = 0)
        }
    }
}

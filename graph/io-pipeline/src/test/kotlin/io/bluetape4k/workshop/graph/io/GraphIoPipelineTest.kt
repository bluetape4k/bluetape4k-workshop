package io.bluetape4k.workshop.graph.io

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.graph.io.checkpoint.GraphImportCheckpointConflictException
import io.bluetape4k.graph.io.checkpoint.GraphImportCheckpointSession
import io.bluetape4k.graph.io.checkpoint.GraphImportCheckpointPhase
import io.bluetape4k.graph.io.checkpoint.InMemoryGraphImportCheckpointStore
import io.bluetape4k.graph.io.options.GraphImportOptions
import io.bluetape4k.graph.io.options.MissingEndpointPolicy
import io.bluetape4k.graph.io.options.copyWithCheckpointSourceIdentity
import io.bluetape4k.graph.io.report.GraphIoFailureSeverity
import io.bluetape4k.graph.io.report.GraphIoFileRole
import io.bluetape4k.graph.io.report.GraphIoFormat
import io.bluetape4k.graph.io.report.GraphIoPhase
import io.bluetape4k.graph.io.report.GraphIoStatus
import io.bluetape4k.graph.io.support.GraphIoExternalIdMap
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

class GraphIoPipelineTest {

    @Test
    fun `imports deterministic CSV fixture into TinkerGraph`() {
        TinkerGraphOperations().use { ops ->
            val pipeline = GraphIoPipeline(ops)

            val report = pipeline.importCsv(fixture("vertices.csv"), fixture("edges.csv"))

            report.status shouldBeEqualTo GraphIoStatus.COMPLETED
            report.format shouldBeEqualTo GraphIoFormat.CSV
            report.failures shouldHaveSize 0
            report.verticesRead shouldBeEqualTo 3L
            report.verticesCreated shouldBeEqualTo 3L
            report.edgesRead shouldBeEqualTo 2L
            report.edgesCreated shouldBeEqualTo 2L
            assertGraphState(ops, expectOriginalExternalIds = true)
        }
    }

    @Test
    fun `resumes CSV checkpoint after edge failure without duplicating target vertices`(@TempDir tempDir: Path) {
        val vertices = tempDir.resolve("vertices.csv").also {
            it.writeText("id,label\nv1,Person\nv2,Person\n")
        }
        val edges = tempDir.resolve("edges.csv").also {
            it.writeText("id,label,from,to\ne1,CONTRIBUTES_TO,v1,missing\n")
        }
        val store = InMemoryGraphImportCheckpointStore()
        val options = GraphImportOptions(
            batchSize = 2,
            checkpointStore = store,
            checkpointKey = "graph-io-pipeline-csv",
            checkpointSourceIdentity = "fixture-v1",
        )

        TinkerGraphOperations().use { operations ->
            val pipeline = GraphIoPipeline(operations)
            val failed = pipeline.importCsv(vertices, edges, options)

            failed.status shouldBeEqualTo GraphIoStatus.FAILED
            store.load("graph-io-pipeline-csv")?.phase shouldBeEqualTo GraphImportCheckpointPhase.FAILED
            store.load("graph-io-pipeline-csv")?.verticesProcessed shouldBeEqualTo 2L
            totalVertices(operations) shouldBeEqualTo 2
            totalEdges(operations) shouldBeEqualTo 0

            assertFailsWith<GraphImportCheckpointConflictException> {
                pipeline.importCsv(
                    vertices,
                    edges,
                    options.copyWithCheckpointSourceIdentity(
                        checkpointSourceIdentity = "different-fixture",
                        resumeFromCheckpoint = true,
                    ),
                )
            }

            assertFailsWith<GraphImportCheckpointConflictException> {
                pipeline.importCsv(
                    vertices,
                    edges,
                    options.copyWithCheckpointSourceIdentity(
                        onMissingEdgeEndpoint = MissingEndpointPolicy.SKIP_EDGE,
                        resumeFromCheckpoint = true,
                    ),
                )
            }

            edges.writeText("id,label,from,to\ne1,CONTRIBUTES_TO,v1,v2\n")
            val resumed = pipeline.importCsv(
                vertices,
                edges,
                options.copyWithCheckpointSourceIdentity(resumeFromCheckpoint = true),
            )

            resumed.status shouldBeEqualTo GraphIoStatus.COMPLETED
            resumed.verticesCreated shouldBeEqualTo 0L
            resumed.edgesCreated shouldBeEqualTo 1L
            store.load("graph-io-pipeline-csv") shouldBeEqualTo null
            totalVertices(operations) shouldBeEqualTo 2
            totalEdges(operations) shouldBeEqualTo 1
        }
    }

    @Test
    fun `resumes Jackson3 checkpoint after edge failure without duplicating target vertices`(@TempDir tempDir: Path) {
        val source = tempDir.resolve("graph.ndjson").also {
            it.writeText(
                """
                {"type":"vertex","id":"v1","label":"Person","properties":{}}
                {"type":"vertex","id":"v2","label":"Person","properties":{}}
                {"type":"edge","id":"e1","label":"CONTRIBUTES_TO","from":"v1","to":"missing","properties":{}}
                """.trimIndent(),
            )
        }
        val store = InMemoryGraphImportCheckpointStore()
        val options = GraphImportOptions(
            batchSize = 2,
            checkpointStore = store,
            checkpointKey = "graph-io-pipeline-jackson3",
            checkpointSourceIdentity = "ndjson-v1",
        )

        TinkerGraphOperations().use { operations ->
            val pipeline = GraphIoPipeline(operations)
            pipeline.importJackson3NdJson(source, options).status shouldBeEqualTo GraphIoStatus.FAILED
            store.load("graph-io-pipeline-jackson3")?.phase shouldBeEqualTo GraphImportCheckpointPhase.FAILED
            totalVertices(operations) shouldBeEqualTo 2

            source.writeText(
                """
                {"type":"vertex","id":"v1","label":"Person","properties":{}}
                {"type":"vertex","id":"v2","label":"Person","properties":{}}
                {"type":"edge","id":"e1","label":"CONTRIBUTES_TO","from":"v1","to":"v2","properties":{}}
                """.trimIndent(),
            )
            val resumed = pipeline.importJackson3NdJson(
                source,
                options.copyWithCheckpointSourceIdentity(resumeFromCheckpoint = true),
            )

            resumed.status shouldBeEqualTo GraphIoStatus.COMPLETED
            resumed.verticesCreated shouldBeEqualTo 0L
            resumed.edgesCreated shouldBeEqualTo 1L
            store.load("graph-io-pipeline-jackson3") shouldBeEqualTo null
            totalVertices(operations) shouldBeEqualTo 2
            totalEdges(operations) shouldBeEqualTo 1
        }
    }

    @Test
    fun `resumes GraphML checkpoint after edge failure without duplicating target vertices`(@TempDir tempDir: Path) {
        val source = tempDir.resolve("graph.graphml").also {
            it.writeText(graphMlWithEdgeTarget("missing"))
        }
        val store = InMemoryGraphImportCheckpointStore()
        val options = GraphImportOptions(
            batchSize = 2,
            checkpointStore = store,
            checkpointKey = "graph-io-pipeline-graphml",
            checkpointSourceIdentity = "graphml-v1",
        )

        TinkerGraphOperations().use { operations ->
            val pipeline = GraphIoPipeline(operations)
            pipeline.importGraphMl(source, options).status shouldBeEqualTo GraphIoStatus.FAILED
            store.load("graph-io-pipeline-graphml")?.phase shouldBeEqualTo GraphImportCheckpointPhase.FAILED
            totalVertices(operations) shouldBeEqualTo 2

            source.writeText(graphMlWithEdgeTarget("v2"))
            val resumed = pipeline.importGraphMl(
                source,
                options.copyWithCheckpointSourceIdentity(resumeFromCheckpoint = true),
            )

            resumed.status shouldBeEqualTo GraphIoStatus.COMPLETED
            resumed.verticesCreated shouldBeEqualTo 0L
            resumed.edgesCreated shouldBeEqualTo 1L
            store.load("graph-io-pipeline-graphml") shouldBeEqualTo null
            totalVertices(operations) shouldBeEqualTo 2
            totalEdges(operations) shouldBeEqualTo 1
        }
    }

    @Test
    fun `checkpoint fencing rejects a concurrent owner and releases after close`() {
        val store = InMemoryGraphImportCheckpointStore()
        val options = GraphImportOptions(
            checkpointStore = store,
            checkpointKey = "graph-io-pipeline-claim",
        )
        val idMap = GraphIoExternalIdMap(options.onDuplicateVertexId)
        val owner = GraphImportCheckpointSession(
            format = GraphIoFormat.CSV,
            sourceIdentity = "claim-source-v1",
            options = options,
            idMap = idMap,
        )

        assertFailsWith<GraphImportCheckpointConflictException> {
            GraphImportCheckpointSession(
                format = GraphIoFormat.CSV,
                sourceIdentity = "claim-source-v1",
                options = options.copyWithCheckpointSourceIdentity(resumeFromCheckpoint = true),
                idMap = GraphIoExternalIdMap(options.onDuplicateVertexId),
            )
        }

        val checkpoint = requireNotNull(store.load("graph-io-pipeline-claim"))
        store.save(
            "graph-io-pipeline-claim",
            checkpoint.withMetadata(checkpoint.importOptionsIdentity, "stale-owner"),
        )
        assertFailsWith<GraphImportCheckpointConflictException> {
            owner.verticesCommitted(1)
        }

        owner.close()
        val resumed = GraphImportCheckpointSession(
            format = GraphIoFormat.CSV,
            sourceIdentity = "claim-source-v1",
            options = options.copyWithCheckpointSourceIdentity(resumeFromCheckpoint = true),
            idMap = GraphIoExternalIdMap(options.onDuplicateVertexId),
        )
        resumed.completed()
        store.load("graph-io-pipeline-claim") shouldBeEqualTo null
    }

    @Test
    fun `exports and imports Jackson3 NDJSON round trip`(@TempDir tempDir: Path) {
        TinkerGraphOperations().use { sourceOps ->
            val source = GraphIoPipeline(sourceOps, exportChunkSize = 2)
            source.importCsv(fixture("vertices.csv"), fixture("edges.csv"))

            val target = inside(tempDir, "graph.ndjson")
            val exportReport = source.exportJackson3NdJson(target)

            assertExportReport(exportReport, GraphIoFormat.NDJSON_JACKSON3)
            assertSmallExport(target)

            TinkerGraphOperations().use { importedOps ->
                val importReport = GraphIoPipeline(importedOps).importJackson3NdJson(target)

                importReport.status shouldBeEqualTo GraphIoStatus.COMPLETED
                importReport.format shouldBeEqualTo GraphIoFormat.NDJSON_JACKSON3
                importReport.failures shouldHaveSize 0
                importReport.verticesCreated shouldBeEqualTo 3L
                importReport.edgesCreated shouldBeEqualTo 2L
                assertGraphState(importedOps, expectOriginalExternalIds = false)
            }
        }
        assertTempOutputs(tempDir, setOf("graph.ndjson"))
    }

    @Test
    fun `rejects a non-positive graph export chunk size`() {
        TinkerGraphOperations().use { ops ->
            assertFailsWith<IllegalArgumentException> {
                GraphIoPipeline(ops, exportChunkSize = 0)
            }
        }
    }

    @Test
    fun `exports and imports GraphML round trip with fail closed options`(@TempDir tempDir: Path) {
        TinkerGraphOperations().use { sourceOps ->
            val source = GraphIoPipeline(sourceOps)
            source.importCsv(fixture("vertices.csv"), fixture("edges.csv"))

            val target = inside(tempDir, "graph.graphml")
            val exportReport = source.exportGraphMl(target)

            assertExportReport(exportReport, GraphIoFormat.GRAPHML)
            assertSmallExport(target)

            TinkerGraphOperations().use { importedOps ->
                val importReport = GraphIoPipeline(importedOps).importGraphMl(target)

                importReport.status shouldBeEqualTo GraphIoStatus.COMPLETED
                importReport.format shouldBeEqualTo GraphIoFormat.GRAPHML
                importReport.failures shouldHaveSize 0
                importReport.verticesCreated shouldBeEqualTo 3L
                importReport.edgesCreated shouldBeEqualTo 2L
                assertGraphState(importedOps, expectOriginalExternalIds = false)
            }
        }
        assertTempOutputs(tempDir, setOf("graph.graphml"))
    }

    @Test
    fun `blank vertex id returns failed report without graph mutation`(@TempDir tempDir: Path) {
        val vertices = tempDir.resolve("blank-vertices.csv").also {
            it.writeText("id,label,prop.code\n,Person,blank\n")
        }
        val edges = tempDir.resolve("blank-edges.csv").also {
            it.writeText("id,label,from,to,prop.code\n")
        }

        TinkerGraphOperations().use { ops ->
            val report = GraphIoPipeline(ops).importCsv(vertices, edges)

            report.status shouldBeEqualTo GraphIoStatus.FAILED
            report.failures shouldHaveSize 1
            report.failures.single().severity shouldBeEqualTo GraphIoFailureSeverity.ERROR
            report.failures.single().phase shouldBeEqualTo GraphIoPhase.READ_VERTEX
            report.failures.single().fileRole shouldBeEqualTo GraphIoFileRole.VERTICES
            report.failures.single().message.lowercase() shouldContain "blank"
            report.verticesCreated shouldBeEqualTo 0L
            report.edgesCreated shouldBeEqualTo 0L
            totalVertices(ops) shouldBeEqualTo 0
            totalEdges(ops) shouldBeEqualTo 0
        }
    }

    @Test
    fun `missing edge endpoint returns failed report without target graph mutation`(@TempDir tempDir: Path) {
        val vertices = tempDir.resolve("missing-endpoint-vertices.csv").also {
            it.writeText("id,label,prop.code\nperson-alice,Person,alice\n")
        }
        val edges = tempDir.resolve("missing-endpoint-edges.csv").also {
            it.writeText("id,label,from,to,prop.code\nbad-edge,REVIEWS,person-alice,missing-project,bad\n")
        }

        TinkerGraphOperations().use { ops ->
            val report = GraphIoPipeline(ops).importCsv(vertices, edges)

            report.status shouldBeEqualTo GraphIoStatus.FAILED
            report.failures shouldHaveSize 1
            report.failures.single().severity shouldBeEqualTo GraphIoFailureSeverity.ERROR
            report.failures.single().phase shouldBeEqualTo GraphIoPhase.READ_EDGE
            report.failures.single().fileRole shouldBeEqualTo GraphIoFileRole.EDGES
            report.failures.single().message.lowercase() shouldContain "endpoint"
            report.verticesCreated shouldBeEqualTo 1L
            report.edgesCreated shouldBeEqualTo 0L
            totalVertices(ops) shouldBeEqualTo 0
            totalEdges(ops) shouldBeEqualTo 0
        }
    }

    @Test
    fun `unsupported GraphML construct fails closed without created elements`(@TempDir tempDir: Path) {
        val graphml = tempDir.resolve("unsupported-port.graphml").also {
            it.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
                  <graph id="G" edgedefault="directed">
                    <node id="n1">
                      <port name="p1"/>
                    </node>
                  </graph>
                </graphml>
                """.trimIndent(),
            )
        }

        TinkerGraphOperations().use { ops ->
            val report = GraphIoPipeline(ops).importGraphMl(graphml)

            report.status shouldBeEqualTo GraphIoStatus.FAILED
            report.format shouldBeEqualTo GraphIoFormat.GRAPHML
            report.failures shouldHaveSize 1
            report.failures.single().severity shouldBeEqualTo GraphIoFailureSeverity.ERROR
            report.failures.single().elementName shouldBeEqualTo "port"
            report.verticesCreated shouldBeEqualTo 0L
            report.edgesCreated shouldBeEqualTo 0L
            totalVertices(ops) shouldBeEqualTo 0
            totalEdges(ops) shouldBeEqualTo 0
        }
    }

    @Test
    fun `missing CSV source fails fast before import`(@TempDir tempDir: Path) {
        val vertices = tempDir.resolve("missing-vertices.csv")
        val edges = tempDir.resolve("edges.csv").also {
            it.writeText("id,label,from,to,prop.code\n")
        }

        TinkerGraphOperations().use { ops ->
            assertFailsWith<IllegalArgumentException> {
                GraphIoPipeline(ops).importCsv(vertices, edges)
            }
            totalVertices(ops) shouldBeEqualTo 0
            totalEdges(ops) shouldBeEqualTo 0
        }
    }

    @Test
    fun `directory CSV source fails fast before import`(@TempDir tempDir: Path) {
        val vertices = tempDir.resolve("vertices-dir").createDirectories()
        val edges = tempDir.resolve("edges.csv").also {
            it.writeText("id,label,from,to,prop.code\n")
        }

        TinkerGraphOperations().use { ops ->
            assertFailsWith<IllegalArgumentException> {
                GraphIoPipeline(ops).importCsv(vertices, edges)
            }
            totalVertices(ops) shouldBeEqualTo 0
            totalEdges(ops) shouldBeEqualTo 0
        }
    }

    @Test
    fun `directory export target fails fast without writing files`(@TempDir tempDir: Path) {
        TinkerGraphOperations().use { ops ->
            GraphIoPipeline(ops).importCsv(fixture("vertices.csv"), fixture("edges.csv"))

            assertFailsWith<IllegalArgumentException> {
                GraphIoPipeline(ops).exportGraphMl(tempDir)
            }
            assertTempOutputs(tempDir, emptySet())
        }
    }

    @Test
    fun `fixture values are safe for documentation examples`() {
        val forbiddenPrefixes = setOf('=', '+', '-', '@')
        listOf(fixture("vertices.csv"), fixture("edges.csv")).forEach { file ->
            file.readText()
                .lineSequence()
                .filter { it.isNotBlank() }
                .flatMap { it.split(',') }
                .forEach { cell ->
                    (cell.firstOrNull() !in forbiddenPrefixes).shouldBeTrue()
                    cell.none { it == '/' || it == '\\' }.shouldBeTrue()
                    cell.none { it.code < 32 }.shouldBeTrue()
                    val lower = cell.lowercase()
                    (lower.contains("token") || lower.contains("password") || lower.contains("secret")).shouldBeFalse()
                }
        }
    }

    private fun fixture(name: String): Path {
        val url = javaClass.classLoader.getResource("graph-io-pipeline/$name")
            .requireNotNull("graph-io-pipeline/$name")
        return Path.of(url.toURI())
    }

    private fun assertExportReport(report: io.bluetape4k.graph.io.report.GraphExportReport, format: GraphIoFormat) {
        report.status shouldBeEqualTo GraphIoStatus.COMPLETED
        report.format shouldBeEqualTo format
        report.failures shouldHaveSize 0
        report.verticesWritten shouldBeEqualTo 3L
        report.edgesWritten shouldBeEqualTo 2L
        report.skippedVertices shouldBeEqualTo 0L
        report.skippedEdges shouldBeEqualTo 0L
    }

    private fun assertSmallExport(path: Path) {
        path.exists().shouldBeTrue()
        path.isRegularFile().shouldBeTrue()
        Files.size(path) shouldBeGreaterThan 0L
        (Files.size(path) < 10_000L).shouldBeTrue()
    }

    private fun assertGraphState(ops: GraphOperations, expectOriginalExternalIds: Boolean) {
        val people = ops.findVerticesByLabel("Person")
        val projects = ops.findVerticesByLabel("Project")
        val contributes = ops.findEdgesByLabel("CONTRIBUTES_TO")
        val reviews = ops.findEdgesByLabel("REVIEWS")

        people shouldHaveSize 2
        projects shouldHaveSize 1
        contributes shouldHaveSize 1
        reviews shouldHaveSize 1

        people.map { it.properties["code"] }.toSet() shouldBeEqualTo setOf("alice", "bob")
        projects.single().properties["code"] shouldBeEqualTo "graphio"
        contributes.single().properties["code"] shouldBeEqualTo "alice-project"
        reviews.single().properties["code"] shouldBeEqualTo "bob-project"

        assertPropertyKeys(people + projects, setOf("code", "name", "kind", "_graphIoExternalId"))
        assertPropertyKeys(contributes + reviews, setOf("code", "role", "_graphIoExternalId"))

        if (expectOriginalExternalIds) {
            people.associate { it.properties["code"] to it.properties["_graphIoExternalId"] } shouldBeEqualTo
                mapOf("alice" to "person-alice", "bob" to "person-bob")
            projects.single().properties["_graphIoExternalId"] shouldBeEqualTo "project-graphio"
        }

        val verticesById = (people + projects).associateBy { it.id }
        contributes.single().let { edge ->
            verticesById.getValue(edge.startId).properties["code"] shouldBeEqualTo "alice"
            verticesById.getValue(edge.endId).properties["code"] shouldBeEqualTo "graphio"
        }
        reviews.single().let { edge ->
            verticesById.getValue(edge.startId).properties["code"] shouldBeEqualTo "bob"
            verticesById.getValue(edge.endId).properties["code"] shouldBeEqualTo "graphio"
        }
    }

    private fun assertPropertyKeys(elements: Collection<Any>, expected: Set<String>) {
        elements.forEach { element ->
            val properties = when (element) {
                is io.bluetape4k.graph.model.GraphVertex -> element.properties
                is io.bluetape4k.graph.model.GraphEdge -> element.properties
                else -> error("Unsupported graph element: $element")
            }
            properties.keys shouldBeEqualTo expected
        }
    }

    private fun totalVertices(ops: GraphOperations): Int =
        ops.findVerticesByLabel("Person").size + ops.findVerticesByLabel("Project").size

    private fun totalEdges(ops: GraphOperations): Int =
        ops.findEdgesByLabel("CONTRIBUTES_TO").size + ops.findEdgesByLabel("REVIEWS").size

    private fun graphMlWithEdgeTarget(target: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <graphml xmlns="http://graphml.graphdrawing.org/xmlns">
          <key id="label" for="all" attr.name="label" attr.type="string"/>
          <graph id="G" edgedefault="directed">
            <node id="v1"><data key="label">Person</data></node>
            <node id="v2"><data key="label">Person</data></node>
            <edge id="e1" source="v1" target="$target"><data key="label">CONTRIBUTES_TO</data></edge>
          </graph>
        </graphml>
        """.trimIndent()

    private fun inside(tempDir: Path, relative: String): Path {
        val root = tempDir.normalize().toAbsolutePath()
        val target = root.resolve(relative).normalize().toAbsolutePath()
        target.startsWith(root).shouldBeTrue()
        target.parent.createDirectories()
        target.isDirectory().shouldBeFalse()
        return target
    }

    @OptIn(ExperimentalPathApi::class)
    private fun assertTempOutputs(tempDir: Path, expectedNames: Set<String>) {
        val actual = tempDir.walk()
            .filter { it.isRegularFile() }
            .map { it.relativeTo(tempDir).toString() }
            .toSet()

        actual shouldBeEqualTo expectedNames
        actual.forEach { name ->
            (Path.of(name).extension in setOf("ndjson", "graphml")).shouldBeTrue()
        }
    }
}

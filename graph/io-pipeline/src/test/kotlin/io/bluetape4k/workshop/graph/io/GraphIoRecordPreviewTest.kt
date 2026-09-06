package io.bluetape4k.workshop.graph.io

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.graph.io.jackson3.Jackson3NdJsonRecordFlowReader
import io.bluetape4k.graph.io.report.GraphIoReadException
import io.bluetape4k.graph.io.source.GraphImportSource
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.junit5.coroutines.runSuspendIO
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

class GraphIoRecordPreviewTest {

    @Test
    fun `previews CSV records in input order without resolving edge endpoints`() {
        runSuspendIO {
            val preview = GraphIoRecordPreview().csv(fixture("vertices.csv"), fixture("edges.csv"), limit = 2)

            preview.vertices.map { it.externalId } shouldBeEqualTo listOf("person-alice", "person-bob")
            preview.edges.map { it.externalId } shouldBeEqualTo listOf("edge-alice-project", "edge-bob-project")
            preview.edges.map { it.fromExternalId } shouldBeEqualTo listOf("person-alice", "person-bob")
            preview.edges.map { it.toExternalId } shouldBeEqualTo listOf("project-graphio", "project-graphio")
        }
    }

    @Test
    fun `previews exported NDJSON and GraphML with the same bounded contract`(@TempDir tempDir: Path) {
        runSuspendIO {
            val ndjson = tempDir.resolve("graph.ndjson")
            val graphml = tempDir.resolve("graph.graphml")
            TinkerGraphOperations().use { operations ->
                val pipeline = GraphIoPipeline(operations)
                pipeline.importCsv(fixture("vertices.csv"), fixture("edges.csv"))
                pipeline.exportJackson3NdJson(ndjson)
                pipeline.exportGraphMl(graphml)
            }

            val previewer = GraphIoRecordPreview()
            val ndjsonPreview = previewer.jackson3NdJson(ndjson, limit = 2)
            val graphMlPreview = previewer.graphMl(graphml, limit = 2)

            ndjsonPreview.vertices.map { it.properties["code"] } shouldBeEqualTo listOf("alice", "bob")
            graphMlPreview.vertices.map { it.properties["code"] } shouldBeEqualTo listOf("alice", "bob")
            ndjsonPreview.edges.map { it.properties["code"] } shouldBeEqualTo listOf("alice-project", "bob-project")
            graphMlPreview.edges.map { it.properties["code"] } shouldBeEqualTo listOf("alice-project", "bob-project")
            ndjsonPreview.edges.all { it.fromExternalId.isNotBlank() && it.toExternalId.isNotBlank() }.shouldBeTrue()
            graphMlPreview.edges.all { it.fromExternalId.isNotBlank() && it.toExternalId.isNotBlank() }.shouldBeTrue()
        }
    }

    @Test
    fun `reader flow is cold and take honors stream ownership`(@TempDir tempDir: Path) {
        runSuspendIO {
            val ndjson = tempDir.resolve("graph.ndjson")
            TinkerGraphOperations().use { operations ->
                val pipeline = GraphIoPipeline(operations)
                pipeline.importCsv(fixture("vertices.csv"), fixture("edges.csv"))
                pipeline.exportJackson3NdJson(ndjson)
            }
            val bytes = ndjson.readBytes()
            val ownedInput = TrackingInputStream(bytes)
            val callerOwnedInput = TrackingInputStream(bytes)
            val ownedFlow = Jackson3NdJsonRecordFlowReader().readVertices(
                GraphImportSource.InputStreamSource(ownedInput, closeInput = true),
            )
            val callerOwnedFlow = Jackson3NdJsonRecordFlowReader().readVertices(
                GraphImportSource.InputStreamSource(callerOwnedInput, closeInput = false),
            )

            ownedInput.wasRead.shouldBeFalse()
            callerOwnedInput.wasRead.shouldBeFalse()
            ownedFlow.take(1).toList().size shouldBeEqualTo 1
            callerOwnedFlow.take(1).toList().size shouldBeEqualTo 1
            ownedInput.wasRead.shouldBeTrue()
            callerOwnedInput.wasRead.shouldBeTrue()
            ownedInput.wasClosed.shouldBeTrue()
            callerOwnedInput.wasClosed.shouldBeFalse()
        }
    }

    @Test
    fun `malformed NDJSON exposes only redacted phase and line`(@TempDir tempDir: Path) {
        runSuspendIO {
            val secret = "secret-customer-payload"
            val source = tempDir.resolve("malformed.ndjson").also {
                it.writeText("{ not-json: $secret }\n")
            }

            val error = assertFailsWith<GraphIoReadException> {
                GraphIoRecordPreview().jackson3NdJson(source, limit = 1)
            }

            error.message.orEmpty().contains(secret).shouldBeFalse()
            error.failure.message.contains(secret).shouldBeFalse()
            error.failure.location shouldBeEqualTo "line:1"
            (error.failure.sourceName == null).shouldBeTrue()
        }
    }

    @Test
    fun `rejects a non-positive preview limit`() {
        runSuspendIO {
            assertFailsWith<IllegalArgumentException> {
                GraphIoRecordPreview().csv(fixture("vertices.csv"), fixture("edges.csv"), limit = 0)
            }
        }
    }

    private fun fixture(name: String): Path =
        Path.of("src/test/resources/graph-io-pipeline/$name").toAbsolutePath().normalize()

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var wasRead: Boolean = false
            private set
        var wasClosed: Boolean = false
            private set

        override fun read(): Int {
            wasRead = true
            return super.read()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            wasRead = true
            return super.read(bytes, offset, length)
        }

        override fun close() {
            wasClosed = true
            super.close()
        }
    }
}

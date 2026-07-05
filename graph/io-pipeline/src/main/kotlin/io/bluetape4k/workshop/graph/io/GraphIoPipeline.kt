package io.bluetape4k.workshop.graph.io

import io.bluetape4k.graph.io.csv.CsvGraphBulkImporter
import io.bluetape4k.graph.io.csv.CsvGraphImportSource
import io.bluetape4k.graph.io.graphml.GraphMlBulkExporter
import io.bluetape4k.graph.io.graphml.GraphMlBulkImporter
import io.bluetape4k.graph.io.graphml.GraphMlImportOptions
import io.bluetape4k.graph.io.graphml.UnsupportedGraphMlElementPolicy
import io.bluetape4k.graph.io.jackson3.Jackson3NdJsonBulkExporter
import io.bluetape4k.graph.io.jackson3.Jackson3NdJsonBulkImporter
import io.bluetape4k.graph.io.options.DuplicateVertexPolicy
import io.bluetape4k.graph.io.options.GraphExportOptions
import io.bluetape4k.graph.io.options.GraphImportOptions
import io.bluetape4k.graph.io.options.MissingEndpointPolicy
import io.bluetape4k.graph.io.report.GraphExportReport
import io.bluetape4k.graph.io.report.GraphImportReport
import io.bluetape4k.graph.io.report.GraphIoStatus
import io.bluetape4k.graph.io.source.GraphExportSink
import io.bluetape4k.graph.io.source.GraphImportSource
import io.bluetape4k.graph.model.BatchEdge
import io.bluetape4k.graph.model.GraphEdge
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotNull
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Workshop pipeline that imports a small CSV graph and exports it as NDJSON or GraphML.
 *
 * The pipeline keeps the graph-io adapter contracts visible for learners:
 * CSV uses separate vertex and edge files, export labels are explicit, duplicate
 * vertex ids fail fast, missing edge endpoints fail fast, and GraphML rejects
 * unsupported constructs instead of silently skipping them.
 *
 * Example:
 *
 * ```kotlin
 * val pipeline = GraphIoPipeline(tinkerGraphOperations)
 * pipeline.importCsv(Path.of("vertices.csv"), Path.of("edges.csv"))
 * pipeline.exportJackson3NdJson(Path.of("build/graph.ndjson"))
 * ```
 */
class GraphIoPipeline(
    private val operations: GraphOperations,
) {

    /**
     * Imports vertices and edges from deterministic CSV files.
     *
     * The vertex file must contain `id` and `label` columns, and the edge file
     * must contain `from` and `to` endpoint columns. The original CSV id is
     * preserved in `_graphIoExternalId` for documentation and inspection. CSV
     * rows are imported into a scratch TinkerGraph first, then copied into the
     * target graph only after the adapter returns `COMPLETED`.
     *
     * @return import report from the scratch graph-io adapter; callers should
     * check `GraphIoStatus.COMPLETED` and empty `failures` before continuing.
     */
    fun importCsv(vertices: Path, edges: Path): GraphImportReport {
        val readableVertices = requireReadableFile(vertices, "vertices")
        val readableEdges = requireReadableFile(edges, "edges")

        TinkerGraphOperations().use { scratch ->
            val report = CsvGraphBulkImporter().importGraph(
                source = CsvGraphImportSource(
                    vertices = GraphImportSource.PathSource(readableVertices),
                    edges = GraphImportSource.PathSource(readableEdges),
                ),
                operations = scratch,
                options = importOptions,
            )
            if (report.status == GraphIoStatus.COMPLETED) {
                copyScratchGraphToTarget(scratch)
            }
            return report
        }
    }

    /**
     * Exports the current workshop graph to Jackson 3 NDJSON.
     *
     * Only the labels used by this example are exported, making the output
     * deterministic and small enough for focused round-trip tests. The target
     * path is normalized and must not point at an existing directory.
     *
     * @return export report; callers should check `GraphIoStatus.COMPLETED` and
     * empty `failures` before using the generated NDJSON file.
     */
    fun exportJackson3NdJson(target: Path): GraphExportReport =
        Jackson3NdJsonBulkExporter().exportGraph(
            sink = GraphExportSink.PathSink(requireWritableTarget(target, "target")),
            operations = operations,
            options = exportOptions,
        )

    /**
     * Imports a Jackson 3 NDJSON export back into the graph.
     *
     * The same fail-fast duplicate and missing-endpoint policies are used as
     * the CSV import path so learners see one consistent import contract. The
     * source path is normalized and must exist as a regular file.
     *
     * @return import report; callers should check `GraphIoStatus.COMPLETED` and
     * empty `failures` before reading from the target graph.
     */
    fun importJackson3NdJson(source: Path): GraphImportReport =
        Jackson3NdJsonBulkImporter().importGraph(
            source = GraphImportSource.PathSource(requireReadableFile(source, "source")),
            operations = operations,
            options = importOptions,
        )

    /**
     * Exports the current workshop graph to GraphML.
     *
     * GraphML keeps labels and properties in a single XML document while the
     * export filter still uses the same explicit vertex and edge label sets.
     * The target path is normalized and must not point at an existing directory.
     *
     * @return export report; callers should check `GraphIoStatus.COMPLETED` and
     * empty `failures` before sharing the generated GraphML file.
     */
    fun exportGraphMl(target: Path): GraphExportReport =
        GraphMlBulkExporter().exportGraph(
            sink = GraphExportSink.PathSink(requireWritableTarget(target, "target")),
            operations = operations,
            options = exportOptions,
        )

    /**
     * Imports GraphML using strict unsupported-element handling.
     *
     * Unsupported GraphML constructs such as `port` are reported as failures
     * instead of being skipped, which keeps workshop smoke tests deterministic.
     * The source path is normalized and must exist as a regular file.
     *
     * @return import report; callers should check `GraphIoStatus.COMPLETED` and
     * empty `failures` before reading from the target graph.
     */
    fun importGraphMl(source: Path): GraphImportReport =
        GraphMlBulkImporter().importGraph(
            source = GraphImportSource.PathSource(requireReadableFile(source, "source")),
            operations = operations,
            options = importOptions,
            graphMlOptions = graphMlImportOptions,
        )

    private fun requireReadableFile(path: Path, role: String): Path {
        val normalized = path.normalize().toAbsolutePath()
        normalized.exists().toMissingCount().requireInRange(0, 0, "$role.exists")
        normalized.isRegularFile().toMissingCount().requireInRange(0, 0, "$role.regularFile")
        return normalized
    }

    private fun requireWritableTarget(path: Path, role: String): Path {
        val normalized = path.normalize().toAbsolutePath()
        normalized.isDirectory().toViolationCount().requireInRange(0, 0, "$role.directory")
        return normalized
    }

    private fun copyScratchGraphToTarget(scratch: GraphOperations) {
        val vertices = readWorkshopVertices(scratch)
        val createdByScratchId = vertices
            .groupBy { it.label }
            .flatMap { (label, labeledVertices) ->
                operations.createVertices(label, labeledVertices.map { it.properties })
                    .zip(labeledVertices)
                    .map { (created, original) -> original.id to created.id }
            }
            .toMap()

        readWorkshopEdges(scratch)
            .groupBy { it.label }
            .forEach { (label, labeledEdges) ->
                operations.createEdges(
                    label = label,
                    edges = labeledEdges.map { edge ->
                        BatchEdge(
                            fromId = createdByScratchId.requiredId(edge.startId),
                            toId = createdByScratchId.requiredId(edge.endId),
                            properties = edge.properties,
                        )
                    },
                )
            }
    }

    private fun readWorkshopVertices(ops: GraphOperations): List<GraphVertex> =
        vertexLabels.flatMap { label -> ops.findVerticesByLabel(label) }

    private fun readWorkshopEdges(ops: GraphOperations): List<GraphEdge> =
        edgeLabels.flatMap { label -> ops.findEdgesByLabel(label) }

    private fun Map<GraphElementId, GraphElementId>.requiredId(id: GraphElementId): GraphElementId =
        this[id].requireNotNull("copiedVertex[${id.value}]")

    private companion object {
        private const val EXTERNAL_ID_PROPERTY = "_graphIoExternalId"

        private val vertexLabels = setOf("Person", "Project")
        private val edgeLabels = setOf("CONTRIBUTES_TO", "REVIEWS")

        private val importOptions = GraphImportOptions(
            onDuplicateVertexId = DuplicateVertexPolicy.FAIL,
            onMissingEdgeEndpoint = MissingEndpointPolicy.FAIL,
            preserveExternalIdProperty = EXTERNAL_ID_PROPERTY,
        )

        private val exportOptions = GraphExportOptions(
            vertexLabels = vertexLabels,
            edgeLabels = edgeLabels,
        )

        private val graphMlImportOptions = GraphMlImportOptions(
            unsupportedElementPolicy = UnsupportedGraphMlElementPolicy.FAIL,
        )
    }
}

private fun Boolean.toMissingCount(): Int = if (this) 0 else 1

private fun Boolean.toViolationCount(): Int = if (this) 1 else 0

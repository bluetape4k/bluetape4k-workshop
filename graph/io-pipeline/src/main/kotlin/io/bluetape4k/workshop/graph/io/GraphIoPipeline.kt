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
import io.bluetape4k.graph.repository.DEFAULT_GRAPH_EXPORT_CHUNK_SIZE
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * 작은 CSV 그래프를 가져와 NDJSON 또는 GraphML로 내보내는 워크숍 pipeline입니다.
 *
 * 이 pipeline은 학습자가 graph-io 어댑터 계약을 직접 확인할 수 있게 합니다.
 * CSV는 정점 파일과 간선 파일을 분리하고, 내보낼 label은 명시하며, 중복
 * 정점 ID와 누락된 간선 endpoint는 조기 실패합니다. GraphML도 지원하지 않는
 * 구조를 조용히 건너뛰지 않고 거부합니다.
 *
 * [exportChunkSize]는 Graph 0.6.0 chunk-aware exporter가 repository에서 한 번에
 * 읽을 최대 레코드 수입니다. 작은 값으로 설정하면 bounded export 동작을 로컬에서
 * 확인할 수 있고, 기본값은 library의 [DEFAULT_GRAPH_EXPORT_CHUNK_SIZE]입니다.
 *
 * 예:
 *
 * ```kotlin
 * val pipeline = GraphIoPipeline(tinkerGraphOperations)
 * pipeline.importCsv(Path.of("vertices.csv"), Path.of("edges.csv"))
 * pipeline.exportJackson3NdJson(Path.of("build/graph.ndjson"))
 * ```
 */
class GraphIoPipeline(
    private val operations: GraphOperations,
    private val exportChunkSize: Int = DEFAULT_GRAPH_EXPORT_CHUNK_SIZE,
) {

    init {
        exportChunkSize.requirePositiveNumber("exportChunkSize")
    }

    private val exportOptions: GraphExportOptions
        get() = GraphExportOptions(
            vertexLabels = vertexLabels,
            edgeLabels = edgeLabels,
            exportChunkSize = exportChunkSize,
        )

    /**
     * 결정적인 CSV 파일에서 정점과 간선을 가져옵니다.
     *
     * 정점 파일에는 `id`와 `label` 열이 있어야 하고, 간선 파일에는 `from`과
     * `to` endpoint 열이 있어야 합니다. 원본 CSV ID는 문서화와 점검을 위해
     * `_graphIoExternalId`에 보존합니다. CSV 행은 먼저 scratch TinkerGraph로
     * 가져온 뒤, 어댑터가 `COMPLETED`를 반환한 경우에만 대상 그래프로 복사합니다.
     *
     * @return scratch graph-io 어댑터의 import report입니다. 호출자는 계속하기 전에
     * `GraphIoStatus.COMPLETED`와 빈 `failures`를 확인해야 합니다.
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
     * 현재 워크숍 그래프를 Jackson 3 NDJSON으로 내보냅니다.
     *
     * 이 예제에서 쓰는 label만 내보내므로 출력이 결정적이고 집중적인 round-trip
     * 테스트에 충분히 작습니다. 대상 경로는 정규화되며 기존 디렉터리를 가리키면 안 됩니다.
     *
     * @return export report입니다. 호출자는 생성된 NDJSON 파일을 사용하기 전에
     * `GraphIoStatus.COMPLETED`와 빈 `failures`를 확인해야 합니다.
     */
    fun exportJackson3NdJson(target: Path): GraphExportReport =
        Jackson3NdJsonBulkExporter().exportGraph(
            sink = GraphExportSink.PathSink(requireWritableTarget(target, "target")),
            operations = operations,
            options = exportOptions,
        )

    /**
     * Jackson 3 NDJSON export를 다시 그래프로 가져옵니다.
     *
     * CSV import 경로와 같은 중복 조기 실패 및 누락 endpoint 정책을 사용하므로
     * 학습자는 일관된 import 계약 하나를 확인할 수 있습니다. 소스 경로는 정규화되며
     * 일반 파일로 존재해야 합니다.
     *
     * @return import report입니다. 호출자는 대상 그래프에서 읽기 전에
     * `GraphIoStatus.COMPLETED`와 빈 `failures`를 확인해야 합니다.
     */
    fun importJackson3NdJson(source: Path): GraphImportReport =
        Jackson3NdJsonBulkImporter().importGraph(
            source = GraphImportSource.PathSource(requireReadableFile(source, "source")),
            operations = operations,
            options = importOptions,
        )

    /**
     * 현재 워크숍 그래프를 GraphML로 내보냅니다.
     *
     * GraphML은 label과 속성을 하나의 XML 문서에 담지만, export filter는 동일하게
     * 명시적인 정점 및 간선 label 집합을 사용합니다. 대상 경로는 정규화되며 기존
     * 디렉터리를 가리키면 안 됩니다.
     *
     * @return export report입니다. 호출자는 생성된 GraphML 파일을 공유하기 전에
     * `GraphIoStatus.COMPLETED`와 빈 `failures`를 확인해야 합니다.
     */
    fun exportGraphMl(target: Path): GraphExportReport =
        GraphMlBulkExporter().exportGraph(
            sink = GraphExportSink.PathSink(requireWritableTarget(target, "target")),
            operations = operations,
            options = exportOptions,
        )

    /**
     * 지원하지 않는 요소를 엄격히 처리하며 GraphML을 가져옵니다.
     *
     * `port` 같은 지원하지 않는 GraphML 구조는 건너뛰지 않고 실패로 보고하므로
     * 워크숍 smoke test가 결정적으로 유지됩니다. 소스 경로는 정규화되며 일반 파일로
     * 존재해야 합니다.
     *
     * @return import report입니다. 호출자는 대상 그래프에서 읽기 전에
     * `GraphIoStatus.COMPLETED`와 빈 `failures`를 확인해야 합니다.
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

        private val graphMlImportOptions = GraphMlImportOptions(
            unsupportedElementPolicy = UnsupportedGraphMlElementPolicy.FAIL,
        )
    }
}

private fun Boolean.toMissingCount(): Int = if (this) 0 else 1

private fun Boolean.toViolationCount(): Int = if (this) 1 else 0

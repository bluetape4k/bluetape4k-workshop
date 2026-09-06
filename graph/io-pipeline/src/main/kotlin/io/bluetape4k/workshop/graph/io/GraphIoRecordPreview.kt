package io.bluetape4k.workshop.graph.io

import io.bluetape4k.graph.io.contract.GraphRecordFlowReader
import io.bluetape4k.graph.io.csv.CsvGraphImportSource
import io.bluetape4k.graph.io.csv.CsvGraphRecordFlowReader
import io.bluetape4k.graph.io.graphml.GraphMlRecordFlowReader
import io.bluetape4k.graph.io.jackson3.Jackson3NdJsonRecordFlowReader
import io.bluetape4k.graph.io.model.GraphIoEdgeRecord
import io.bluetape4k.graph.io.model.GraphIoVertexRecord
import io.bluetape4k.graph.io.source.GraphImportSource
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import java.nio.file.Path

/**
 * 그래프를 변경하지 않고 graph-io 원시 레코드를 제한된 개수만 미리 봅니다.
 *
 * reader가 반환하는 cold [kotlinx.coroutines.flow.Flow]는 collect 시점에 source를
 * 열고 입력 순서를 유지합니다. 간선 endpoint는 아직 외부 ID이며, 실제 graph ID로
 * resolve하는 책임은 bulk importer에 남아 있습니다.
 */
class GraphIoRecordPreview {

    /** CSV의 정점/간선 파일을 각각 [limit]개까지 읽습니다. */
    suspend fun csv(vertices: Path, edges: Path, limit: Int): GraphIoRecordPreviewResult {
        val source = CsvGraphImportSource(
            vertices = GraphImportSource.PathSource(vertices),
            edges = GraphImportSource.PathSource(edges),
        )
        return read(CsvGraphRecordFlowReader(), source, limit)
    }

    /** Jackson 3 NDJSON 파일의 정점/간선을 각각 [limit]개까지 읽습니다. */
    suspend fun jackson3NdJson(source: Path, limit: Int): GraphIoRecordPreviewResult =
        read(Jackson3NdJsonRecordFlowReader(), GraphImportSource.PathSource(source), limit)

    /** GraphML 파일의 정점/간선을 각각 [limit]개까지 읽습니다. */
    suspend fun graphMl(source: Path, limit: Int): GraphIoRecordPreviewResult =
        read(GraphMlRecordFlowReader(), GraphImportSource.PathSource(source), limit)

    private suspend fun <S : Any> read(
        reader: GraphRecordFlowReader<S>,
        source: S,
        limit: Int,
    ): GraphIoRecordPreviewResult {
        limit.requirePositiveNumber("limit")
        return GraphIoRecordPreviewResult(
            vertices = reader.readVertices(source).take(limit).toList(),
            edges = reader.readEdges(source).take(limit).toList(),
        )
    }
}

/** 제한된 원시 정점/간선 미리보기 결과입니다. */
data class GraphIoRecordPreviewResult(
    val vertices: List<GraphIoVertexRecord>,
    val edges: List<GraphIoEdgeRecord>,
)

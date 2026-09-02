# graph-io-pipeline

[English](README.md) | 한국어

## 아키텍처

이 모듈은 작은 TinkerGraph 파이프라인으로 `bluetape4k-graph`의 graph-io
import/export adapter를 학습하는 예제입니다. smoke 경로는 의도적으로 컨테이너를
쓰지 않습니다. 로컬 CSV fixture를 읽고 `GraphOperations`로 TinkerGraph에 기록한 뒤,
Jackson 3 NDJSON과 GraphML로 export하고, 각 파일을 새 TinkerGraph에 다시 import하여
report를 확인합니다.

> **관련 이슈:** [bluetape4k-workshop #287](https://github.com/bluetape4k/bluetape4k-workshop/issues/287), [#860](https://github.com/bluetape4k/bluetape4k-workshop/issues/860)

![Graph IO Pipeline Architecture](../../docs/images/readme-diagrams/graph-io-pipeline-readme-architecture-01.png)

SVG source: [graph-io-pipeline-readme-architecture-01.svg](../../docs/images/readme-diagrams/graph-io-pipeline-readme-architecture-01.svg)

## 개요

이 예제의 질문은 좁습니다. TinkerGraph를 코드로 직접 seed하지 않고 graph-io
adapter로 graph fixture를 넣어야 할 때가 언제인가?

주요 학습 내용:

- `CsvGraphBulkImporter`로 vertex CSV와 edge CSV를 함께 import합니다.
- 원래 fixture id를 `_graphIoExternalId`에 보존합니다.
- import한 graph를 `Jackson3NdJsonBulkExporter`와 `GraphMlBulkExporter`로 export합니다.
- NDJSON과 GraphML을 새 `TinkerGraphOperations` 인스턴스에 import합니다.
- `GraphIoStatus.COMPLETED`, 빈 `failures`, vertex/edge 수, label, property, topology를 검증합니다.
- 생성 파일은 JUnit `@TempDir` 아래에만 둡니다.

![Graph IO Pipeline Round-trip Sequence](../../docs/images/readme-diagrams/graph-io-pipeline-readme-sequence-01.png)

SVG source: [graph-io-pipeline-readme-sequence-01.svg](../../docs/images/readme-diagrams/graph-io-pipeline-readme-sequence-01.svg)

## Adapter 선택 표

| Adapter | 입력 형태 | 적합한 용도 | Class / format | 장점 | 제약과 주의점 | 의존성 상태 | 쓰지 않는 편이 나은 경우 |
|---------|----------|-------------|----------------|------|---------------|-------------|--------------------------|
| CSV | `vertices.csv` + `edges.csv` 쌍 | 작은 결정적 fixture와 spreadsheet로 보기 쉬운 sample | `CsvGraphBulkImporter`, `GraphIoFormat.CSV` | 눈으로 확인하기 쉽고 fixture diff가 단순하며 endpoint column이 명시적입니다 | 파일 확장자 자동 감지는 없습니다. text 중심 값이 가장 안전합니다. paired-file 형태라 single-stream format과 다릅니다 | 이 모듈에서 사용 | 임의 upload endpoint나 풍부한 typed property round-trip에는 부적합합니다 |
| Jackson3 NDJSON | line-delimited JSON 단일 stream | 기계가 읽기 좋은 export/import test와 append 가능한 local artifact | `Jackson3NdJsonBulkExporter`, `Jackson3NdJsonBulkImporter`, `GraphIoFormat.NDJSON_JACKSON3` | 단일 파일이고 compact하며 diff와 streaming tool에 잘 맞습니다 | path 검증, stream ownership, atomic-write 정책은 호출자가 책임집니다 | 이 모듈에서 사용 | GraphML interchange file이 필요한 경우에는 부적합합니다 |
| GraphML | XML 문서 하나 | GraphML을 이해하는 도구와의 interchange | `GraphMlBulkExporter`, `GraphMlBulkImporter`, `GraphIoFormat.GRAPHML` | 표준적인 graph 문서 형태이며 key와 label을 읽기 쉽습니다 | 이 예제는 `UnsupportedGraphMlElementPolicy.FAIL`을 사용합니다. `port`, nested graph, hyperedge 등 미지원 구조는 fail-closed 처리됩니다. 신뢰된 local/exported file에만 사용합니다 | 이 모듈에서 사용 | untrusted upload sanitizer나 미지원 GraphML dialect 처리에는 부적합합니다 |
| Okio-backed streams | graph-io stream을 감싸는 Okio source/sink | custom filesystem, buffering, compression 경계를 graph-io 주변에 둘 때 | `bluetape4k-graph-io-okio` bridge APIs | 호출자가 이미 Okio stream 구성을 소유할 때 유용합니다 | CSV + Okio는 stream bridge이지 고수준 compression/encryption 정책이 아닙니다. stream close와 atomic write는 호출자가 책임집니다 | 문서화만 함. 이 모듈은 import하지 않음 | source code가 Okio API를 실제로 쓰지 않는 smoke 예제에는 추가하지 않는 편이 낫습니다 |

## Report 의미

모든 graph-io 메서드는 report를 반환합니다. report는 로그가 아니라 계약입니다.

- `COMPLETED` + 빈 `failures`: 이 예제의 정상 경로입니다.
- `PARTIAL`: option에 따라 일부 record가 skip되었습니다. 이 예제는 partial import를 성공으로 보지 않습니다.
- `FAILED`: 오류에서 import/export가 중단되었습니다. failure detail은 maintainer용 진단 정보이므로 public endpoint에서 그대로 반환하지 않습니다.

path 입력은 adapter 실행 전에 검증합니다. 존재하지 않는 파일, directory source,
directory export target은 대상 graph를 변경하지 않고 즉시 실패합니다.

이 모듈이 export한 NDJSON과 GraphML에는 의도적으로 보존한 `_graphIoExternalId`를
포함한 graph property가 들어갑니다. backend가 생성한 graph id는 round trip마다
안정적이지 않으므로, test는 label, `code` 값, count, edge topology를 비교합니다.

## 진행 지표

선택 기능인 `bluetape4k-graph-io-micrometer` bridge는 동일한 graph-io lifecycle
이벤트를 cardinality가 낮은 Micrometer meter로 변환합니다. 애플리케이션 callback과
metric이 같은 import 또는 export 실행을 관찰해야 하면 composite listener를
`GraphIoPipeline`에 전달합니다.

```kotlin
import io.bluetape4k.graph.io.micrometer.GraphIoMicrometerProgressListener
import io.bluetape4k.graph.io.report.GraphIoCompositeProgressListener
import io.bluetape4k.graph.io.report.GraphIoProgressListener
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

val registry = SimpleMeterRegistry()
val userListener = GraphIoProgressListener { event ->
    check(event.runId > 0L)
}
val listener = GraphIoCompositeProgressListener.of(
    userListener,
    GraphIoMicrometerProgressListener(registry),
)
val pipeline = GraphIoPipeline(operations, progressListener = listener)
val report = pipeline.importCsv(vertices, edges)
check(report.status == GraphIoStatus.COMPLETED)
```

bridge는 `graph.io.runs`, `graph.io.records`, `graph.io.bytes`,
`graph.io.duration`, `graph.io.phase.duration`, `graph.io.active`를 기록합니다.
tag는 `operation`, `format`, `status`, `kind`, `phase`의 소문자 enum 값만
사용합니다. dataset 경로, record ID, run ID, exception message를 metric tag에
추가하면 안 됩니다. terminal event가 발생하면 `graph.io.active`는 0으로
돌아가며, 실패한 실행도 `status=failed` counter와 timer로 확인할 수 있습니다.

## 사용 예

### CSV import

```kotlin
import io.bluetape4k.graph.io.report.GraphIoStatus
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.workshop.graph.io.GraphIoPipeline
import java.nio.file.Path

TinkerGraphOperations().use { operations ->
    val pipeline = GraphIoPipeline(operations)
    val report = pipeline.importCsv(
        vertices = Path.of("src/test/resources/graph-io-pipeline/vertices.csv"),
        edges = Path.of("src/test/resources/graph-io-pipeline/edges.csv"),
    )

    check(report.status == GraphIoStatus.COMPLETED)
    check(report.failures.isEmpty())
}
```

### Jackson 3 NDJSON round trip

```kotlin
import io.bluetape4k.graph.io.report.GraphIoStatus
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.workshop.graph.io.GraphIoPipeline
import java.nio.file.Path

val ndjson = Path.of("build/graph-io-pipeline/graph.ndjson")
val vertices = Path.of("src/test/resources/graph-io-pipeline/vertices.csv")
val edges = Path.of("src/test/resources/graph-io-pipeline/edges.csv")

TinkerGraphOperations().use { sourceOps ->
    val source = GraphIoPipeline(sourceOps)
    val seedReport = source.importCsv(vertices, edges)
    check(seedReport.status == GraphIoStatus.COMPLETED)
    check(seedReport.failures.isEmpty())

    val exportReport = source.exportJackson3NdJson(ndjson)
    check(exportReport.status == GraphIoStatus.COMPLETED)
    check(exportReport.failures.isEmpty())
}

TinkerGraphOperations().use { targetOps ->
    val importReport = GraphIoPipeline(targetOps).importJackson3NdJson(ndjson)
    check(importReport.status == GraphIoStatus.COMPLETED)
    check(importReport.failures.isEmpty())
}
```

### GraphML round trip

```kotlin
import io.bluetape4k.graph.io.report.GraphIoStatus
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.workshop.graph.io.GraphIoPipeline
import java.nio.file.Path

val graphml = Path.of("build/graph-io-pipeline/graph.graphml")
val vertices = Path.of("src/test/resources/graph-io-pipeline/vertices.csv")
val edges = Path.of("src/test/resources/graph-io-pipeline/edges.csv")

TinkerGraphOperations().use { sourceOps ->
    val source = GraphIoPipeline(sourceOps)
    val seedReport = source.importCsv(vertices, edges)
    check(seedReport.status == GraphIoStatus.COMPLETED)
    check(seedReport.failures.isEmpty())

    val exportReport = source.exportGraphMl(graphml)
    check(exportReport.status == GraphIoStatus.COMPLETED)
    check(exportReport.failures.isEmpty())
}

TinkerGraphOperations().use { targetOps ->
    val importReport = GraphIoPipeline(targetOps).importGraphMl(graphml)
    check(importReport.status == GraphIoStatus.COMPLETED)
    check(importReport.failures.isEmpty())
}
```

### Checkpoint를 사용한 import와 재개

`bluetape4k-graph` 1.0.0은 CSV, Jackson 3 NDJSON, GraphML importer에
opt-in checkpoint lifecycle을 추가했습니다. `GraphIoPipeline`은
`GraphImportOptions`를 그대로 전달하므로, 중단된 import를 이미 commit한
vertex를 다시 만들지 않고 재개할 수 있습니다.

```kotlin
import io.bluetape4k.graph.io.checkpoint.InMemoryGraphImportCheckpointStore
import io.bluetape4k.graph.io.options.GraphImportOptions
import io.bluetape4k.graph.io.options.copyWithCheckpointSourceIdentity
import io.bluetape4k.graph.io.report.GraphIoStatus
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.workshop.graph.io.GraphIoPipeline
import java.nio.file.Path
import kotlin.io.path.writeText

val vertices = Path.of("build/graph-io-pipeline/vertices.csv")
val edges = Path.of("build/graph-io-pipeline/edges.csv")
val checkpointStore = InMemoryGraphImportCheckpointStore()
val options = GraphImportOptions(
    batchSize = 2,
    checkpointStore = checkpointStore,
    checkpointKey = "graph-io-pipeline",
    // 실패한 source를 수정하는 동안 이 identity는 안정적으로 유지합니다.
    checkpointSourceIdentity = "fixture-v1",
)

TinkerGraphOperations().use { operations ->
    val pipeline = GraphIoPipeline(operations)
    val first = pipeline.importCsv(vertices, edges, options)
    check(first.status == GraphIoStatus.FAILED)

    // 실패한 edge를 수정한 뒤 저장된 vertex/id-map checkpoint에서 재개합니다.
    edges.writeText("id,label,from,to\ne1,CONTRIBUTES_TO,v1,v2\n")
    val resumed = pipeline.importCsv(
        vertices,
        edges,
        options.copyWithCheckpointSourceIdentity(resumeFromCheckpoint = true),
    )

    check(resumed.status == GraphIoStatus.COMPLETED)
    check(resumed.verticesCreated == 0L)
    check(checkpointStore.load("graph-io-pipeline") == null)
}
```

Checkpoint mode는 복원된 external-id map이 같은 backend vertex를 가리키도록
CSV를 대상 graph에 직접 import합니다. 따라서 실패하면 대상 graph에 partial
state가 남을 수 있습니다. graph와 checkpoint store가 atomic transaction을
공유하지 않는 한 재개는 at-least-once로 취급하고, 안정적인 external ID 또는
unique constraint를 적용해야 합니다. 기본 `GraphIoPipeline` 호출은 위에서
설명한 scratch graph import/copy 경로를 그대로 유지합니다.

## Manual TinkerGraph seed에서 이동하기

기존 graph workshop module은 여전히 domain traversal 예제입니다. schema, repository
call, traversal behavior, backend 차이를 설명합니다. `graph-io-pipeline`은 다릅니다.
반복 가능한 fixture import/export, adapter 선택, report 확인을 가르칠 때 사용합니다.

이 모듈을 upload endpoint template로 쓰면 안 됩니다. GraphML과 NDJSON import는
신뢰된 local workshop file을 대상으로 한 예제일 뿐 sanitizer가 아닙니다. 신뢰할 수
없는 file은 graph-io에 넘기기 전에 validation, sandbox, size limit, audit가 필요합니다.

## 테스트 실행

```bash
./gradlew :graph-io-pipeline:test
```

smoke 경로는 TinkerGraph와 local file만 사용합니다. Docker, Testcontainers, Neo4j,
Memgraph, PostgreSQL은 필요하지 않습니다.

## 의존성

```kotlin
dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    implementation(libs.bluetape4k.graph.io.core)
    implementation(libs.bluetape4k.graph.io.csv)
    implementation(libs.bluetape4k.graph.io.graphml)
    implementation(libs.bluetape4k.graph.io.jackson3)
    implementation(libs.bluetape4k.graph.io.micrometer)
}
```

bluetape4k 버전은 repository 수준의 `bluetape4k-dependencies` platform이 관리하며,
root build에서 `platform(libs.bluetape4k.dependencies)`로 적용합니다. 이 consumer
workshop module은 version 없는 alias만 선언하며, 개별 graph BOM을 import하지 않습니다.

## 관련 문서

- [bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph) — graph library source
- [graph-social-network](../social-network/README.ko.md) — traversal 중심 social graph 예제
- [graph-knowledge-graph](../knowledge-graph/README.ko.md) — heterogeneous graph model 예제
- [bluetape4k-workshop #287](https://github.com/bluetape4k/bluetape4k-workshop/issues/287) — tracking issue

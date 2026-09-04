# graph-knowledge-graph

[English](README.md) | 한국어

## 아키텍처

[bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph) 라이브러리를 활용한 지식 그래프(knowledge graph) 구축 및 탐색 예제입니다.
동일한 그래프 모델을 블로킹 서비스와 코루틴 서비스로 제공하고, 같은 서비스 로직을 TinkerGraph, Neo4j,
Memgraph 백엔드에서 실행합니다.

두 서비스는 출시된 `io.bluetape4k.graph.repository.requireEndpoint` extension으로 그래프
endpoint를 검증합니다. 정점이 없거나 label이 일치하지 않으면 `IllegalArgumentException`으로
즉시 실패하며, 검증 메시지에는 호출자 parameter name을 그대로 보존합니다.

> **관련 이슈:** [bluetape4k-workshop #11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11)

![graph-knowledge-graph 아키텍처 다이어그램](../../docs/images/readme-diagrams/graph-knowledge-graph-readme-architecture-01.png)

## 개요

이 모듈은 프로그래밍 언어, 프레임워크, 라이브러리, 런타임 플랫폼, 그리고 이들을 참조하는 문서를 모델링한 **기술 지식 그래프**를 구현합니다.

주요 학습 내용:

- `VertexLabel` / `EdgeLabel` 스키마 DSL로 이종 엔티티 타입 모델링
- 문서가 어떤 엔티티를 언급하는지 기록 (`MENTIONS` 엣지 + confidence 점수)
- 엔티티 간 의미 관계 표현 (`RELATED_TO` 엣지 + relationType)
- 엔티티를 어휘 개념으로 분류 (`IS_A` 엣지)
- 설정 가능한 hop 깊이로 그래프 탐색
- 두 엔티티 사이의 연관 경로 추론 (깊이/건수 제한)
- Entity/Concept/Document 키의 schema drift를 `2.0.0`의
  `GraphSchemaDriftPlanner` 계약으로 계획 (기본 dry-run)
- 동일한 서비스 로직을 여러 그래프 백엔드(TinkerGraph, Neo4j, Memgraph)에서 실행

## 도메인 모델

시드 그래프는 **기술 도메인** 시나리오를 사용합니다:

![Knowledge Graph Domain Model](../../docs/images/readme-diagrams/graph-knowledge-graph-readme-domain-model-01.png)

### 버텍스 타입

| Label    | 키 프로퍼티 | 예시 값 |
|----------|------------|---------|
| Entity   | entityId   | Kotlin, Spring, JVM, Coroutines |
| Concept  | conceptId  | Programming Language, Framework, Library, Platform |
| Document | documentId | "Kotlin in Action", "Spring Boot Reference" |

### 엣지 타입

| Label      | 방향 | 프로퍼티 | 의미 |
|------------|------|---------|------|
| MENTIONS   | Doc → Entity | confidence (0–100) | 문서가 엔티티를 언급 |
| RELATED_TO | Entity → Entity | relationType | 의미 관계 |
| IS_A       | Entity → Concept | — | 엔티티가 개념으로 분류됨 |

### 시드 토폴로지

![Knowledge Graph Seed Topology](../../docs/images/readme-diagrams/graph-knowledge-graph-readme-seed-topology-01.png)

## 핵심 기능

### 엔티티/개념/문서 관리

```kotlin
val service = KnowledgeGraphService(ops, "knowledge_graph")
service.initialize()

val paper = service.addDocument("doc-1", "Graph API Guide", "docs")
val kotlin = service.addEntity("entity-kotlin", "Kotlin", "Language")
val language = service.addConcept("concept-language", "Programming Language", "software")

service.mention(paper.id, kotlin.id, confidence = 95)
service.classify(kotlin.id, language.id)
```

### 그래프 탐색

```kotlin
// 문서가 어떤 엔티티를 언급하는가?
val mentioned = service.findMentionedEntities(paper.id)

// Kotlin과 관련된 엔티티는? (최대 2홉)
val related = service.findRelatedEntities(kotlin.id, depth = 2)

// Kotlin이 속한 개념은?
val concepts = service.findConceptsForEntity(kotlin.id)

// Kotlin에서 Spring까지의 관계 경로는?
val paths = service.inferRelationshipPaths(kotlin.id, spring.id, maxDepth = 3, maxPaths = 5)
```

### Schema drift 계획

`initialize()`는 graph를 만들기 전에 desired schema 계획을 만들고
`GraphSchemaPlan`을 반환합니다. 기본값은 읽기 전용 dry-run이므로 seed 쓰기 전에
안전하게 실행할 수 있습니다. Entity, Concept, Document의 도메인 키마다 lookup
index와 unique constraint 계획을 선언합니다.

```kotlin
val plan = service.initialize()
check(plan.options.dryRun)
println(plan.items.map { it.action })

// 승인된 호출 경계에서만 DDL을 명시적으로 적용합니다.
val approved = service.planSchema(
    GraphSchemaPlanOptions(dryRun = false, allowDestructiveDrops = true),
)
val report = approved.apply(ops.schemaManager())
check(report.unsupported.isEmpty() || report.unsupported.all { it.action == GraphSchemaPlanAction.UNSUPPORTED })
```

서비스는 destructive 옵션을 자동 적용하지 않습니다. TinkerGraph는 unique
constraint 생성을 `UNSUPPORTED`로 보고하고, Neo4j와 Memgraph는 같은 plan/report
모델로 backend capability를 노출합니다. schema 계획이 실패하면 graph 생성과
seed 데이터 쓰기보다 먼저 예외를 전달합니다.

`KnowledgeGraphSchema.desiredSchema()`는 결정적이므로 같은 live metadata와 desired
definition을 반복해도 plan 순서가 동일합니다.

### 코루틴 변형

```kotlin
val service = KnowledgeGraphSuspendService(ops, "knowledge_graph")
val plan = service.initialize() // dry-run schema 계획, DDL 변경 없음

val mentioned: Flow<GraphVertex> = service.findMentionedEntities(paper.id)
val related: Flow<GraphVertex> = service.findRelatedEntities(kotlin.id, depth = 2)
val paths: Flow<GraphPath> = service.inferRelationshipPaths(kotlin.id, spring.id)
```

Suspend 서비스도 `planSchema()`를 제공하며 `runBlocking` 없이 coroutine schema
capability로 backend metadata를 읽습니다. desired schema와 기본 dry-run 계약은
blocking 서비스와 동일합니다.

## 지원 백엔드

| 백엔드      | 클래스                         | Docker 필요 | Gradle 태스크 |
|-------------|-------------------------------|------------|--------------|
| TinkerGraph | `TinkerGraphOperations`       | 불필요      | `test`       |
| Neo4j       | `Neo4jGraphOperations`        | 필요        | `integrationTest` |
| Memgraph    | `MemgraphGraphOperations`     | 필요        | `integrationTest` |

## 테스트 실행

```bash
# 기본 테스트 — TinkerGraph만 (Docker 불필요)
./gradlew :graph-knowledge-graph:test

# 통합 테스트 — Docker 필요 (Neo4j + Memgraph)
./gradlew :graph-knowledge-graph:integrationTest
```

## 의존성

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    compileOnly(libs.bluetape4k.graph.neo4j)
    compileOnly(libs.bluetape4k.graph.memgraph)
}
```

저장소 root는 `platform(libs.bluetape4k.dependencies)`를 import하므로 graph alias는
의도적으로 versionless이며 workshop `2.0.0` BOM을 사용합니다.

## 관련 문서

- [bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph) — 그래프 라이브러리 소스
- [graph-social-network](../social-network/README.ko.md) — 소셜 네트워크 예제
- [graph-abuser-detection](../abuser-detection/README.ko.md) — 어뷰저 탐지 예제
- [bluetape4k-workshop #11](https://github.com/bluetape4k/bluetape4k-workshop/issues/11) — 추적 이슈

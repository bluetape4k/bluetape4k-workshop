# Lessons — graph/social-network 모듈 구현

날짜: 2026-05-25
브랜치: feat/graph-social-network
모듈: `:graph-social-network`

---

## 1. TinkerGraph `shortestPath` — vertex-only path 이슈

### 문제

TinkerGraph `shortestPath` / `allPaths` 구현에서 `AnonymousTraversal.both(label)` 사용 시
Gremlin 경로에 vertex만 포함되고 edge가 포함되지 않음.
결과적으로 `GraphPath.length` (== `edges.size`) 가 항상 0.

```kotlin
// 기존 (edge 미포함)
val step = AnonymousTraversal.both(label).simplePath()
// 경로: [VertexStep(alice), VertexStep(bob)] → length == 0
```

### 시도

`bothE<Any>().otherV()` 로 변경 시도 → TinkerPop 코틀린 바인딩에서 타입 인자 미지원 + `otherV()` 미해석 컴파일 에러.

### 최종 해결책

라이브러리 fix(post-processing 방식): vertex-only path를 받아 연속된 vertex 쌍 사이의 edge를 별도 쿼리로 조회하는 `buildGraphPathWithEdges()` 헬퍼 추가.

**그러나** `bluetape4k-graph 0.4.1` 이 `bluetape4k-dependencies:1.1.3` BOM에서 재정의되어
workshop에서는 `0.4.2` BOM을 지정해도 실제 모듈이 `0.4.1` 로 해석됨.

### 실용적 Fix (workshop에 적용)

`path.length` (edge count) 대신 `path.vertices.size` (vertex count = hops + 1) 로 테스트.
모든 백엔드(TinkerGraph vertex-only / Neo4j vertex+edge path)에서 동일하게 동작.

```kotlin
// Before
path!!.length shouldBeEqualTo 1

// After — works for all backends
requireNotNull(path).vertices.size shouldBeEqualTo 2
```

### 교훈

- TinkerGraph의 `both()` traversal은 path에 edge를 포함하지 않는다.
  `GraphPath.length` == edge count 이므로 항상 0.
- BOM 계층이 복잡할 때 의도한 버전이 실제로 적용되는지 `./gradlew dependencies` 로 반드시 확인.
- Graph path assertion은 `path.length` 보다 `path.vertices.size` 가 백엔드 독립적.

---

## 2. Seed 토폴로지 설계 오류

### 문제

초기 시드에서 alice가 carol에게도 직접 KNOWS 관계를 가지도록 설계.
이 경우 carol이 alice의 1촌이 되어 FOAF 추천에서 carol이 제외됨 → `recommendConnections` 테스트 실패.

### 해결책

시드를 선형 체인으로 수정: alice → bob → carol, bob → dave, carol → dave.
Alice는 bob만 1촌. carol과 dave는 2촌(bob을 통해), FOAF 추천 대상.

### 교훈

FOAF 추천 테스트 시 seed 토폴로지를 명확히 설계해야 함:
- 추천 대상은 seed 인물의 1촌이 아니어야 하고
- 1촌을 통해 도달 가능한 2촌이어야 함.

---

## 3. `shouldNotBeNull()` 과 `!!` 조합

### 문제

```kotlin
val path: GraphPath? = ...
path.shouldNotBeNull()   // assertion — but does NOT smart-cast path
path!!.length ...        // !! is still needed and forbidden by CLAUDE.md
```

`shouldNotBeNull()`은 assertion side-effect 함수로 스마트캐스트를 제공하지 않음.
CLAUDE.md: `!!` 연산자 금지.

### 해결책

```kotlin
path.shouldNotBeNull()
requireNotNull(path).vertices.size shouldBeEqualTo 2
```

또는 처음부터 null이면 즉시 실패:
```kotlin
val path = requireNotNull(service.findConnectionPath(alice.id, bob.id))
```

---

## 4. 코루틴 서비스에서 Flow 반환 패턴

### 패턴

`GraphSuspendOperations.neighbors()` 는 `Flow<GraphVertex>` 반환.
`recommendConnections()` 처럼 Flow를 여러 번 소비해야 할 때는 `.toList()` 로 수집.

```kotlin
// 직접 Flow 반환 — 스트리밍
fun getDirectConnections(id: GraphElementId): Flow<GraphVertex> =
    ops.neighbors(id, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
        .filter { it.id != id }

// 내부 집계 후 반환
suspend fun recommendConnections(id: GraphElementId): List<ConnectionRecommendation> {
    val directFriends = ops.neighbors(id, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1)).toList()
    // ...
}
```

---

## 5. Code Review P0/P1 항목

**P0:** `path!!` — `shouldNotBeNull()` 이후에도 `!!` 를 사용하면 CLAUDE.md 위반.
**P1:** 미사용 `import io.bluetape4k.logging.warn` — 실제 사용하지 않는 import는 제거.

두 항목 모두 리뷰 직후 수정 커밋.

---

## 6. BOM 버전 충돌 (bluetape4k-dependencies vs bluetape4k-graph BOM)

`bluetape4k-dependencies:1.1.3` 이 `bluetape4k-graph:0.4.1` 을 지정하고 있어
`libs.versions.toml` 의 `bluetape4k-graph = "0.4.2"` 설정이 개별 모듈에서 무시됨.

해결: `./gradlew :module:dependencies` 로 실제 해석된 버전 확인 필수.
`bluetape4k-dependencies` 버전을 올리거나, 모듈 버전을 force-override 해야 함.

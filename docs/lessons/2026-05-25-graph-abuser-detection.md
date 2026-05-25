# 교훈: graph/abuser-detection 모듈 구현

**날짜**: 2026-05-25  
**작업자**: debop  
**브랜치**: `feat/graph-abuser-detection`  
**모듈**: `graph/abuser-detection`

---

## 작업 요약

bluetape4k-graph 라이브러리(Neo4j, TinkerGraph 백엔드)를 활용한 어뷰저 탐지 그래프 서비스 구현.  
PageRank 기반 의심 사용자 순위, 사기 링(fraud ring) 탐지, 추천 루프(referral loop) 탐지를 블로킹 및 코루틴(Flow) 이중 서비스로 제공.

---

## 발견된 버그 및 기술 결정

### B1. `log.warn { }` / `log.debug { }` 람다 구문 컴파일 에러

**원인**: `KLogging`의 `log`는 `org.slf4j.Logger` 로우 타입.  
SLF4J 표준 Logger는 람다 형식을 받지 않으므로 `log.warn { "..." }` 구문이 컴파일 실패.

**수정**: bluetape4k 확장 임포트를 명시 추가.
```kotlin
import io.bluetape4k.logging.warn
import io.bluetape4k.logging.debug
```

**교훈**: `log.warn { }` / `log.debug { }` 등 람다 로깅 구문을 사용할 때는  
반드시 `io.bluetape4k.logging.*` 확장 import를 추가해야 함.  
IDE가 자동 import를 제안하지 않을 수 있으므로 수동 확인 필요.

---

### B2. `neo4j.boltUrl` 미해결 — `neo4j.url` 사용

**원인**: `Neo4jContainer`에 `boltUrl` 프로퍼티가 정의되어 있으나, 해당 클래스가 컴파일 클래스패스에 없어 `Neo4jServer.Launcher`에서 `boltUrl`이 unresolved.  
`Neo4jServer`가 `url`을 override로 노출하며, 이 값이 Bolt URL을 포함함.

**수정**: `neo4j.boltUrl` → `neo4j.url` 사용.
```kotlin
// 잘못된 접근
val driver = GraphDatabase.driver(neo4j.boltUrl, ...)

// 올바른 접근
val driver = GraphDatabase.driver(neo4j.url, ...)
```

**교훈**: bluetape4k Testcontainers 래퍼(`XxxServer`)는 `url`을 표준 접근자로 제공.  
원래 Testcontainers 컨테이너 클래스의 프로퍼티에 직접 접근하기 전에  
`XxxServer`의 공개 API를 먼저 확인할 것.

---

### B3. Testcontainers 2.x Neo4j 모듈명 변경

**원인**: Testcontainers 2.x에서 Neo4j 아티팩트가 `org.testcontainers:neo4j`에서  
`org.testcontainers:testcontainers-neo4j`로 변경됨.

**수정**:
```kotlin
// Testcontainers 1.x (구버전)
testImplementation("org.testcontainers:neo4j")

// Testcontainers 2.x (현재)
testImplementation("org.testcontainers:testcontainers-neo4j")
```

**교훈**: Testcontainers 2.x 마이그레이션 시 기존 모듈명이 달라질 수 있음.  
`bluetape4k-testcontainers`의 `libs.versions.toml`에 등록된 alias를 우선 사용하고,  
직접 좌표를 쓸 경우 Testcontainers 2.x 아티팩트명을 확인.

---

### B4. PageRank `vertexLabel` 필터 미적용 시 identifier 버텍스가 상위 점령

**원인**: PageRank를 전체 그래프에 실행하면 User가 아닌 Device, IP 등 식별자(identifier) 버텍스가  
다수의 User와 연결되어 높은 PageRank 점수를 받아 상위를 차지함.  
`limit` 파라미터가 User 버텍스가 아닌 모든 버텍스에 적용됨.

**수정**: `PageRankOptions`에 `vertexLabel` 필터 지정.
```kotlin
val options = PageRankOptions(
    vertexLabel = UserLabel.label,  // User 버텍스에만 limit 적용
    topK = limit
)
val rankings = graphService.pageRank(options)
```

**교훈**: 멀티-레이블 그래프에서 PageRank/중심성 지표를 특정 엔티티 타입에 적용할 때는  
`vertexLabel` 필터를 반드시 지정할 것.  
미지정 시 허브 역할의 보조 노드(Device, IP)가 상위를 독점.

---

### B5. `shouldNotBeEmpty()` / `shouldBeEmpty()` — infix가 아닌 dot 표기

**원인**: Kluent/bluetape4k-assertions의 `shouldNotBeEmpty()`는 infix 함수가 아님.  
`result shouldNotBeEmpty` 형식으로 작성하면 컴파일 에러 발생.

**수정**:
```kotlin
// 잘못된 표기 (컴파일 에러)
result shouldNotBeEmpty

// 올바른 표기
result.shouldNotBeEmpty()
result.shouldBeEmpty()
```

**교훈**: assertions 라이브러리 함수를 처음 사용할 때 infix 여부를 IDE 자동완성으로 확인.  
`shouldXxx()` 형식 함수는 대부분 dot 방식이며, infix는 `shouldBe`, `shouldEqual` 등 일부에 한정.

---

### B6. Flow API에 `mapIndexed` 미존재

**원인**: `kotlinx.coroutines.flow`에는 `List.mapIndexed()`와 동일한 `Flow.mapIndexed()`가 없음.  
직접 사용하면 컴파일 에러.

**수정**: `withIndex().map { }` 패턴으로 대체.
```kotlin
// 컴파일 에러
flow.mapIndexed { index, value -> ... }

// 올바른 패턴
flow.withIndex().map { (index, value) ->
    // index, value 사용
}
```

**교훈**: Flow API는 Collection API의 모든 확장 함수를 제공하지 않음.  
`mapIndexed`, `forEachIndexed` 등 인덱스가 필요한 연산은 `.withIndex().map { (i, v) -> }` 패턴 사용.

---

## 설계 결정

### D1. 블로킹 + 코루틴 이중 서비스 구조

`AbuserDetectionService` (블로킹 API)와 `AbuserDetectionSuspendService` (코루틴/Flow API)를 병렬 제공.

**이유**:
- 블로킹 서비스: 스프링 MVC 통합 및 Java 코드와의 상호운용성 보장
- 코루틴 서비스: WebFlux / 비동기 파이프라인에서 backpressure와 Flow 스트리밍 지원
- 같은 비즈니스 로직을 두 API 스타일로 시연하는 워크샵 목적에도 부합

**적용 기준**: 인프라 드라이버가 코루틴 지원 여부에 따라 구분.  
Neo4j Kotlin 드라이버는 suspend 함수를 지원하므로 코루틴 서비스에 자연스럽게 매핑.

---

### D2. 그래프 격리 — 테스트별 고유 그래프 이름

통합 테스트에서 각 테스트 클래스는 고유한 그래프/데이터베이스 이름을 사용.

**이유**: 테스트 간 데이터 오염 방지. 병렬 실행 시 충돌 방지.  
TinkerGraph는 인메모리 그래프를 독립적으로 생성; Neo4j는 별도 데이터베이스 또는 고유 라벨 prefix 사용.

---

### D3. 테스트 계층화 — TinkerGraph vs Neo4j/Memgraph

| 계층 | 백엔드 | 태그 | Gradle 태스크 |
|------|--------|------|---------------|
| 기본 | TinkerGraph (인메모리) | 없음 | `:test` |
| 통합 | Neo4j / Memgraph | `@Tag("integration")` | `integrationTest` |

**이유**: TinkerGraph는 Docker 없이 동작하여 CI 기본 태스크에서 빠르게 실행 가능.  
Neo4j/Memgraph는 실제 Bolt 프로토콜 동작을 검증하지만 컨테이너 의존성 있음.

---

## 코드 리뷰 결과 (6-R 리뷰)

| 심각도 | 항목 | 조치 |
|--------|------|------|
| P1/HIGH | `runCatching { driver.close() }` 실패 시 로그 없음 | `.onFailure { log.warn(it) { "드라이버 종료 실패" } }` 추가 |
| P1/HIGH | `rankSuspiciousUsers respects limit` 테스트가 크기 미검증 | `shouldHaveSize(2)` assertion 추가 |
| P2/MEDIUM | `detectReferralLoops` 파라미터 검증 누락 | `requirePositiveNumber(maxDepth, "maxDepth")` 추가 |
| P3/LOW | missing seed vertex 로그 레벨이 DEBUG | WARN으로 승격 |
| P3/LOW | unknown label silent skip (로그 없음) | WARN 로깅 추가 |

**핵심 교훈**:
- `runCatching {}` 블록은 반드시 `.onFailure { log.warn(it) { ... } }` 체인으로 실패를 가시화
- limit/size 제약 테스트는 컬렉션 크기를 직접 assertion으로 검증 (`shouldHaveSize`)
- 공개 API의 숫자 파라미터는 `requirePositiveNumber` / `requireNotBlank` 등으로 입구 검증

---

## 최종 테스트 결과

```
TinkerGraph 기준 (기본 :test 태스크):
37 tests, 0 failures, 0 errors
```

Neo4j/Memgraph 통합 테스트는 `integrationTest` Gradle 태스크로 별도 실행.

---

## 미래 참고 사항

| 상황 | 적용 패턴 |
|------|-----------|
| bluetape4k 람다 로깅 | `import io.bluetape4k.logging.warn` 등 확장 import 필수 |
| Testcontainers 래퍼 URL | `XxxServer.url` 사용; 원본 컨테이너 프로퍼티 직접 접근 지양 |
| Testcontainers 2.x Neo4j | `org.testcontainers:testcontainers-neo4j` 아티팩트명 확인 |
| 멀티-레이블 그래프 PageRank | `PageRankOptions(vertexLabel = TargetLabel.label, topK = limit)` 필터 지정 |
| Flow 인덱스 순회 | `.withIndex().map { (index, value) -> }` 패턴 사용 |
| Kluent assertions dot 표기 | `result.shouldNotBeEmpty()` (infix 아님) |
| `runCatching {}` 실패 처리 | `.onFailure { log.warn(it) { ... } }` 체인 필수 |
| limit 제약 테스트 | `shouldHaveSize(N)` assertion으로 직접 크기 검증 |
| 공개 API 파라미터 검증 | `requirePositiveNumber` / `requireNotBlank` 입구 검증 |

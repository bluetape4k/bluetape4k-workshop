# Plan: graph/abuser-detection 워크샵 모듈

**날짜**: 2026-05-25  
**브랜치**: `feat/graph-abuser-detection`  
**Issue**: bluetape4k-workshop#12  
**Spec**: `docs/superpowers/specs/2026-05-25-graph-abuser-detection-design.md`  
**모듈**: `graph/abuser-detection` → Gradle 모듈명: `graph-abuser-detection`  
**참조 구현**: `/Users/debop/work/bluetape4k/bluetape4k-graph/examples/fraud-detection-examples/`  
**스택**: Kotlin 2.3, Java 25, bluetape4k 1.5.0-Beta2, bluetape4k-graph 0.4.2

---

## 핵심 Spec 결정 사항 (task 에 인코딩됨)

1. **§6 label-dispatch**: `vertexLabelToEdgeLabel: Map<String, IdentifierEdgeLabel>` literal map 사용 (`"Device"→USES_DEVICE` 등). `.uppercase()` 비교 절대 금지.
2. **§5.1 findOrCreate**: `ops.findVerticesByLabel(label).firstOrNull { it.properties["key"] == value }` — 먼저 조회 후 없으면 생성.
3. **§9 @TestInstance(PER_CLASS)**: 두 abstract base (`AbstractAbuserDetectionTest`, `AbstractAbuserDetectionSuspendTest`) 모두 필수.
4. **§9 cleanGraph**: `@BeforeEach` 에서 `runCatching { dropGraph }.onFailure { log.warn }` 래핑.
5. **§9 driver ownership**: integration 구상 클래스 `@AfterAll` 이 `driver.close()` 단독 호출; abstract base 는 close 금지.
6. **§9 unique graphName**: `"abuser_neo4j"` / `"abuser_memgraph"` per backend.
7. **§8 integrationTest task**: `tasks.test { excludeTags("integration") }` + `tasks.register<Test>("integrationTest") { includeTags("integration") }`.
8. **Testcontainers**: `Neo4jServer.Launcher.neo4j`, `MemgraphServer.Launcher.memgraph` singleton 사용 — `GenericContainer` 직접 생성 금지.
9. **§10 validation**: 모든 identifier mutator 가 `requireNotBlank` 를 findOrCreate 전에 호출.

---

## Phase 1 — Module Scaffolding (복잡도: low)

### T1-1: `settings.gradle.kts` 에 graph 도메인 등록
- **complexity**: low
- **파일**: `settings.gradle.kts`
- **작업**:
  - `includeModules("graph", false, true)` 삽입 (`gatling` 과 `image-processing` 사이, 알파벳 순)
  - 결과 모듈명: `:graph-abuser-detection`

### T1-2: `gradle/libs.versions.toml` 에 bluetape4k-graph 추가
- **complexity**: low
- **파일**: `gradle/libs.versions.toml`
- **작업**:
  ```toml
  [versions]
  # TODO: bluetape4k-dependencies BOM 이 bluetape4k-graph 를 govern 하면 pin 제거.
  bluetape4k-graph = "0.4.2"
  
  [libraries]
  bluetape4k-graph-bom       = { module = "io.github.bluetape4k.graph:bluetape4k-graph-bom", version.ref = "bluetape4k-graph" }
  bluetape4k-graph-core      = { module = "io.github.bluetape4k.graph:bluetape4k-graph-core" }
  bluetape4k-graph-tinkerpop = { module = "io.github.bluetape4k.graph:bluetape4k-graph-tinkerpop" }
  bluetape4k-graph-neo4j     = { module = "io.github.bluetape4k.graph:bluetape4k-graph-neo4j" }
  bluetape4k-graph-memgraph  = { module = "io.github.bluetape4k.graph:bluetape4k-graph-memgraph" }
  neo4j-java-driver          = { module = "org.neo4j.driver:neo4j-java-driver" }
  ```

### T1-3: `graph/abuser-detection/build.gradle.kts` 생성
- **complexity**: low
- **파일**: `graph/abuser-detection/build.gradle.kts`
- **작업**: spec §8 의 build.gradle.kts 내용 그대로 — BOM platform, core/tinkerpop impl, neo4j/memgraph compileOnly, integrationTest task, `excludeTags("integration")` on default test task

### T1-4: 테스트 리소스 생성
- **complexity**: low
- **파일**:
  - `graph/abuser-detection/src/test/resources/junit-platform.properties`
  - `graph/abuser-detection/src/test/resources/logback-test.xml`
- **작업**:
  - `junit-platform.properties`: `junit.jupiter.execution.parallel.enabled=false`, `junit.jupiter.tags.exclude=integration`
  - `logback-test.xml`: 기존 workshop 모듈 logback-test.xml 복사

---

## Phase 2 — Schema (복잡도: low)

### T2-1: `AbuserDetectionSchema.kt` — 5 vertex label objects + 5 edge label objects
- **complexity**: low
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/schema/AbuserDetectionSchema.kt`
- **작업**:
  - `fraud-detection-examples/FraudDetectionSchema.kt` 패턴 그대로 따름
  - Vertex labels: `UserLabel`, `DeviceLabel`, `IpAddressLabel`, `PhoneNumberLabel`, `PaymentMethodLabel`
  - Edge labels: `UsesDeviceLabel`, `UsesIpLabel`, `HasPhoneLabel`, `UsesPaymentLabel`, `ReferredByLabel`
  - 각 object 는 `VertexLabel` / `EdgeLabel` interface 구현 (bluetape4k-graph-core API 확인 후)
  - 영문 KDoc 필수

---

## Phase 3 — Model Types (복잡도: low)

### T3-1: `IdentifierEdgeLabel.kt` — value class + companion constants
- **complexity**: low
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model/IdentifierEdgeLabel.kt`
- **작업**:
  ```kotlin
  @JvmInline
  value class IdentifierEdgeLabel(val value: String) {
      companion object {
          val USES_DEVICE  = IdentifierEdgeLabel("USES_DEVICE")
          val USES_IP      = IdentifierEdgeLabel("USES_IP")
          val HAS_PHONE    = IdentifierEdgeLabel("HAS_PHONE")
          val USES_PAYMENT = IdentifierEdgeLabel("USES_PAYMENT")
          val all: List<IdentifierEdgeLabel> = listOf(USES_DEVICE, USES_IP, HAS_PHONE, USES_PAYMENT)
      }
  }
  ```
  - 영문 KDoc 필수

### T3-2: `AbuseCluster.kt`, `AbusePath.kt`, `SuspiciousUserScore.kt`
- **complexity**: low
- **파일**:
  - `.../model/AbuseCluster.kt`
  - `.../model/AbusePath.kt`
  - `.../model/SuspiciousUserScore.kt`
- **작업**: spec §5.3 그대로 — `Serializable`, `serialVersionUID = 1L`, `edgeLabel: IdentifierEdgeLabel`, 영문 KDoc

---

## Phase 4 — Blocking Service (복잡도: high)

### T4-1: `AbuserDetectionService.kt` — 전체 구현
- **complexity**: high
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionService.kt`
- **작업**:
  - `initialize()`: `ops.graphExists` 체크 후 `ops.createGraph`
  - **findOrCreate mutators** (각 identifier):
    ```kotlin
    fun addDevice(deviceId: String, platform: String): GraphVertex {
        deviceId.requireNotBlank("deviceId")
        return ops.findVerticesByLabel(DeviceLabel.label)
            .firstOrNull { it.properties["deviceId"] == deviceId }
            ?: ops.createVertex(DeviceLabel.label, mapOf("deviceId" to deviceId, "platform" to platform))
    }
    ```
  - **`findAbuseCluster(seedUserId)`** — spec §6 label-dispatch 알고리즘 전체:
    ```kotlin
    val vertexLabelToEdgeLabel = mapOf(
        "Device"        to IdentifierEdgeLabel.USES_DEVICE,
        "IpAddress"     to IdentifierEdgeLabel.USES_IP,
        "PhoneNumber"   to IdentifierEdgeLabel.HAS_PHONE,
        "PaymentMethod" to IdentifierEdgeLabel.USES_PAYMENT,
    )
    ```
  - **`explainSuspicion(userId)`**: `IdentifierEdgeLabel.all` 순회, `edgeLabel.value` String 전달
  - **`detectReferralLoops`**: `ops.detectCycles(CycleOptions(...))`
  - **`rankSuspiciousUsers`**: `ops.pageRank(PageRankOptions(...))` → User 필터 → limit → SuspiciousUserScore
  - 영문 KDoc 필수

---

## Phase 5 — Suspend Service (복잡도: medium)

### T5-1: `AbuserDetectionSuspendService.kt` — suspend + Flow variants
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionSuspendService.kt`
- **작업**:
  - T4-1 과 동일한 로직; `ops: GraphSuspendOperations` 사용
  - mutators: `suspend fun`
  - `findAbuseCluster`: `suspend fun` returning `AbuseCluster`
  - `explainSuspicion`, `detectReferralLoops`, `rankSuspiciousUsers`: `Flow<T>` (cold flow)
  - Flow 수집 시 `CancellationException` 전파 (runCatching 내 suspend 호출 금지)
  - 영문 KDoc + @param Flow 수집 방법 설명

---

## Phase 6 — Seed Data (복잡도: medium)

### T6-1: `AbuserDetectionSeed.kt` — 결정론적 테스트 픽스처
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/seed/AbuserDetectionSeed.kt`
- **작업**:
  - 고정 3-user × 2-device 공유 시나리오 (테스트 케이스 2, 3 커버)
  - referral cycle A→B→C→A (테스트 케이스 4 커버)
  - 독립 사용자 (테스트 케이스 7 커버)
  - 모든 timestamp 고정 (`"2026-01-01T00:00:00Z"`)
  - 모든 identifier 는 placeholder hash (`"device-hash-a1b2c3"` 등)
  - `fun seedAll(service: AbuserDetectionService): SeedResult` 반환 (user/identifier GraphVertex map)
  - `data class SeedResult(...)` — 테스트에서 seedUserId 등 참조용

---

## Phase 7 — Abstract Test Base Blocking (복잡도: medium)

### T7-1: `AbstractAbuserDetectionTest.kt`
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionTest.kt`
- **작업**:
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 필수
  - `@BeforeEach cleanGraph()`: `runCatching { dropGraph }.onFailure { log.warn }` + `service.initialize()`
  - 12개 테스트 케이스 (happy 7 + failure 5):
    1. creates user and links device
    2. finds shared-device abuse cluster — returns 2 other users (seed excluded)
    3. explains suspicion by shared device and IP
    4. detects referral loops (A→B→C→A)
    5. ranks user at center of identifier sharing as most suspicious
    6. empty graph returns empty cluster
    7. cluster excludes unrelated users
    8. `addDevice with blank deviceId throws IllegalArgumentException`
    9. `addUser with blank userId throws IllegalArgumentException`
    10. `findAbuseCluster with non-existent seedUserId returns empty cluster`
    11. `rankSuspiciousUsers returns empty list on empty graph`
    12. `detectReferralLoops returns empty list when no REFERRED_BY edges exist`
  - Exception tests: `assertFailsWith<IllegalArgumentException> { }`
  - Assertion: `shouldBe`, `shouldContainExactlyInAnyOrder` etc. (bluetape4k-assertions)
  - 백틱 테스트 이름 사용

---

## Phase 8 — Abstract Test Base Suspend (복잡도: medium)

### T8-1: `AbstractAbuserDetectionSuspendTest.kt`
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionSuspendTest.kt`
- **작업**:
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 필수 (T7-1 과 동일)
  - 동일 12개 테스트를 `runTest { }` 로 래핑
  - Flow 테스트: `explainSuspicion(...).toList()`, `rankSuspiciousUsers(...).toList()`
  - suspend exception: `coInvoking { suspendCall } shouldThrow IllegalArgumentException::class`
  - `CancellationException` 재throw — suspend 본문에서 `runCatching` 사용 금지

---

## Phase 9 — TinkerGraph Concrete Tests (복잡도: low)

### T9-1: `AbuserDetectionTinkerGraphTest.kt` + `AbuserDetectionSuspendTinkerGraphTest.kt`
- **complexity**: low
- **파일**:
  - `.../AbuserDetectionTinkerGraphTest.kt`
  - `.../AbuserDetectionSuspendTinkerGraphTest.kt`
- **작업**:
  - `@Tag` 없음 (default run 대상)
  - TinkerGraph `GraphOperations` / `GraphSuspendOperations` 인스턴스 생성 (bluetape4k-graph-tinkerpop API 참조)
  - `graphName = "abuser_tinkergraph"`
  - `AbuserDetectionService(ops, graphName)` / `AbuserDetectionSuspendService(ops, graphName)` 생성
  - `AbstractAbuserDetectionTest` / `AbstractAbuserDetectionSuspendTest` 확장

---

## Phase 10 — Integration Tests (복잡도: medium)

### T10-1: `AbuserDetectionNeo4jTest.kt`
- **complexity**: medium
- **파일**: `.../AbuserDetectionNeo4jTest.kt`
- **작업**:
  - `@Tag("integration")`
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` (상속으로 충분하나 명시 권장)
  - companion object: `val neo4j = Neo4jServer.Launcher.neo4j` (bluetape4k-testcontainers)
  - `graphName = "abuser_neo4j"`
  - `@AfterAll fun teardown()`: `runCatching { dropGraph }.onFailure { log.warn }` + `driver.close()`
  - abstract base 에서 close 호출 없음

### T10-2: `AbuserDetectionMemgraphTest.kt`
- **complexity**: medium
- **파일**: `.../AbuserDetectionMemgraphTest.kt`
- **작업**:
  - T10-1 과 동일 패턴, `MemgraphServer.Launcher.memgraph`
  - `graphName = "abuser_memgraph"`

---

## Phase 11 — README (복잡도: low)

### T11-1: `README.md` + `README.ko.md`
- **complexity**: low
- **파일**:
  - `graph/abuser-detection/README.md`
  - `graph/abuser-detection/README.ko.md`
- **작업**:
  - Architecture diagram 포함 (spec §2 참조)
  - `Skill("bluetape4k-diagram")` 호출하여 SVG+PNG 생성 — 직접 SVG 작성 금지
  - 다이어그램 저장: `docs/images/readme-diagrams/abuser-detection-architecture.{svg,png}`
  - 구조: Architecture → Core Features → Usage Examples → Build/Test

---

## Phase 12 — Final Wiring + Verification (복잡도: low)

### T12-1: 모듈 등록 검증 + TinkerGraph 테스트 실행
- **complexity**: low
- **작업**:
  ```bash
  cd /Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/feat/graph-abuser-detection
  ./gradlew :graph-abuser-detection:build -x test --no-daemon   # compile check
  ./gradlew :graph-abuser-detection:test --no-daemon            # TinkerGraph tests (no Docker)
  ```
  - 결과 기록: pass count, elapsed time
  - 실패 시 → `bugfix-workflow` 호출

### T12-2: CI workflow 확인
- **complexity**: low
- **작업**:
  ```bash
  rg "graph-abuser-detection|graph-abuser" .github/workflows/
  ```
  - 신규 모듈 CI 추가 누락 여부 확인
  - 필요 시 CI path filter 및 job matrix 추가

### T12-3: Lessons 문서 작성 + 커밋 (PR 생성 전 필수)
- **complexity**: low
- **파일**: `docs/lessons/2026-05-25-graph-abuser-detection.md`
- **작업**: root cause, decision, outcome, review misses, future guidance

---

## 실행 순서 (병렬 가능 그룹)

```
P1 (T1-1 ~ T1-4)  → 순차 (서로 의존)
P2 (T2-1)          → P1 완료 후
P3 (T3-1, T3-2)    → P2 완료 후 (병렬 가능)
P4 (T4-1)          → P3 완료 후 (high complexity — Opus)
P5 (T5-1)          → P3 완료 후 (P4 와 병렬 가능)
P6 (T6-1)          → P4, P5 완료 후
P7 (T7-1)          → P6 완료 후 (high complexity — Opus)
P8 (T8-1)          → P6 완료 후 (P7 과 병렬 가능)
P9 (T9-1)          → P7, P8 완료 후
P10 (T10-1, T10-2) → P7, P8 완료 후 (P9 와 병렬 가능)
P11 (T11-1)        → 코드 완성 후 (P9 이후)
P12 (T12-1~3)      → 전체 완료 후
```

---

## DoD 체크리스트

- [ ] T1-1~T1-4: 모듈 스캐폴딩 완료
- [ ] T2-1: AbuserDetectionSchema.kt — 5 vertex + 5 edge labels
- [ ] T3-1~T3-2: 모델 타입 4종 (IdentifierEdgeLabel, AbuseCluster, AbusePath, SuspiciousUserScore)
- [ ] T4-1: AbuserDetectionService — label-dispatch + findOrCreate 구현
- [ ] T5-1: AbuserDetectionSuspendService — Flow variants
- [ ] T6-1: AbuserDetectionSeed — 결정론적 픽스처
- [ ] T7-1: AbstractAbuserDetectionTest — 12 test cases
- [ ] T8-1: AbstractAbuserDetectionSuspendTest — 12 test cases (runTest)
- [ ] T9-1: TinkerGraph 구상 테스트 2종
- [ ] T10-1~T10-2: Neo4j + Memgraph integration 테스트
- [ ] T11-1: README.md + README.ko.md + diagram
- [ ] T12-1: `./gradlew :graph-abuser-detection:test` 전체 통과
- [ ] T12-2: CI workflow 누락 없음
- [ ] T12-3: Lessons 문서 커밋 (PR 전)

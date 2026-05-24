# Plan: graph/abuser-detection 워크샵 모듈

**날짜**: 2026-05-25  
**브랜치**: `feat/graph-abuser-detection`  
**Issue**: bluetape4k-workshop#12  
**Spec**: `docs/superpowers/specs/2026-05-25-graph-abuser-detection-design.md`  
**모듈**: `graph/abuser-detection` → Gradle 모듈명: `graph-abuser-detection`  
**참조 구현**: `/Users/debop/work/bluetape4k/bluetape4k-graph/examples/fraud-detection-examples/`  
**스택**: Kotlin 2.3, Java 25, bluetape4k 1.5.0-Beta2, bluetape4k-graph 0.4.1 (또는 mavenLocal 0.4.2)

---

## 핵심 Spec 결정 사항 (task 에 인코딩됨)

1. **§6 label-dispatch**: `VERTEX_LABEL_TO_EDGE_LABEL` companion object `private val` 에 선언 — 함수 body 에 `val` 로 선언 금지 (매 호출마다 Map 재생성 방지 + 프로젝트 규칙 준수).
2. **§5.1 findOrCreate — server-side filter 사용**: `ops.findVerticesByLabel(label, mapOf("deviceId" to deviceId)).firstOrNull()` — client-side 전체 스캔 금지.
3. **§5.1 findOrCreate idempotency**: 두 번 호출 시 동일 vertex ID 반환 + 중복 vertex 미생성. test case 13 으로 검증.
4. **§5.2 suspend Flow API 차이**: `GraphSuspendVertexRepository.findVerticesByLabel` 는 `Flow<GraphVertex>` 반환 — `List` 아님. `import kotlinx.coroutines.flow.firstOrNull` 필수.
5. **§5.2 `Flow.mapIndexed` 없음**: `rankSuspiciousUsers` suspend 구현은 `.withIndex().map { (idx, s) -> SuspiciousUserScore(s.vertex, s.score, idx + 1) }` 사용.
6. **§9 @TestInstance(PER_CLASS)**: 두 abstract base 모두 필수.
7. **§9 cleanGraph**: `@BeforeEach` 에서 `runCatching { dropGraph }.onFailure { log.warn }` + `service.initialize()` 래핑.
8. **§9 driver ownership**: integration 구상 클래스 `@AfterAll` 이 `driver.close()` 단독 호출; abstract base 는 close 금지.
9. **§9 unique graphName**: `"abuser_neo4j"` / `"abuser_memgraph"` per backend.
10. **§8 integrationTest task**: `tasks.test { excludeTags("integration") }` + `tasks.register<Test>("integrationTest") { includeTags("integration") }`.
11. **Testcontainers**: `Neo4jServer.Launcher.neo4j`, `MemgraphServer.Launcher.memgraph` singleton — `GenericContainer` 직접 생성 금지.
12. **§9 `junit-platform.properties`**: `tags.exclude=integration` 포함 금지 — Gradle `tasks.test { excludeTags }` 만 사용. properties 는 병렬화 설정 전용.
13. **§9 validation**: `requireNotBlank` 를 findOrCreate 전에 호출.
14. **§9 Flow exception 테스트**: `explainSuspicion`, `detectReferralLoops` 는 non-suspend Flow 반환 — validation 은 collect 시 발생. `assertFailsWith<T> { service.explainSuspicion("").toList() }` 사용; `coInvoking { ... } shouldThrow` 사용 금지.
15. **§9 test case 13**: `addDevice` 동일 deviceId 2회 → 동일 vertex ID, duplicate 없음.
16. **§9 test cases 14–15**: Phone/PaymentMethod 분기 + REFERRED_BY 제외 음성 테스트.
17. **REFERRED_BY exclusion 검증**: `findAbuseCluster` 가 REFERRED_BY 만으로 연결된 user 를 결과에서 제외하는 것을 명시적 음성 테스트로 검증 (test case 15).
18. **seed 전략**: `AbuserDetectionSeed` 는 composable helpers (`seedSharedIdentifiers()`, `seedReferralCycle()`, `seedIsolatedUser()`) 로 분리. 각 test 는 `@BeforeEach cleanGraph` 후 필요한 helper 만 호출.
19. **suspend seeder**: T6-1 이 `suspend fun seedAll(service: AbuserDetectionSuspendService): SeedResult` 도 제공.
20. **version resolution**: `bluetape4k-graph 0.4.2` 는 Maven Central 미발행. T1-2 에서 mavenLocal 발행 여부 확인 후 버전 결정.
21. **[P0-Impl-1/P1-Impl-3] Neo4j/Memgraph ops 생성자 database ≠ graphName**: `Neo4jGraphOperations(driver)` — 2번째 인자에 graphName 전달 금지. 생성자 2번째 인자는 Bolt database 이름 (`"neo4j"` / `"memgraph"`). graphName 은 서비스 생성자(`AbuserDetectionService(ops, graphName)`)에만 전달. 모든 4개 integration 테스트(T10-1~T10-4) 동일.
22. **[P0-Impl-2] suspend `findAbuseCluster` + `explainSuspicion` — Flow API 차이**: `ops.neighbors()` / `ops.findEdgesByStartId()` suspend 변형은 `Flow` 반환. `flatMap { Flow }` 는 compile error. `neighbors().toList()` 로 collect 후 flatMap; `explainSuspicion` 는 `flow { ... .collect { emit(...) } }` 로 작성.
23. **[P1-Test-1] suspend `@BeforeEach cleanGraph()` 패턴**: `runCatching {}` 안에 suspend 호출 금지. `@BeforeEach fun cleanGraph() = runTest { try { ... } catch (e: CancellationException) { throw e } catch (e: Exception) { log.warn } }` 형식.
24. **[P1-Test-2] test 16 Flow cancellation**: seed data 필수 + slow consumer (`.onEach { delay(10) }`) 필수. empty graph 에서는 Flow 즉시 완료 → silent pass.
25. **[P1-Test-4] tests 3/4/5/14 구체 assertion**: paths 에 USES_DEVICE/USES_IP edgeLabel 포함; loops 에 cycle 멤버 IDs 포함; ranks.first() 가 seed user1; sharedIdentifiers 에 PhoneNumber + PaymentMethod labels 포함.
26. **[P1-Arch-3] `configurations {}` `.get()` 필수**: `testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())`. 없으면 Kotlin DSL compile error.
27. **[P1-Deliv-1] `seedIsolatedUser` 는 cluster + isolated user 모두 생성**: user1/user2/user3 공유 deviceA + unrelatedUser 고립. user1.id NPE 방지.

---

## Phase 1 — Module Scaffolding (복잡도: low)

### T1-1: `settings.gradle.kts` 에 graph 도메인 등록
- **complexity**: low
- **파일**: `settings.gradle.kts`
- **작업**:
  - `includeModules("graph", false, true)` 삽입 (`graalvm` 과 `image-processing` 사이, 알파벳 순)
  - **실제 파일 확인**: `settings.gradle.kts` lines 26–27: `graalvm → image-processing` 순서; `graph` 는 `graalvm` 뒤에 삽입
  - 결과 모듈명: `:graph-abuser-detection`

### T1-2: `gradle/libs.versions.toml` 에 bluetape4k-graph 추가
- **complexity**: low
- **파일**: `gradle/libs.versions.toml`
- **⚠️ version resolution 주의**: `bluetape4k-graph 0.4.2` 는 Maven Central 미발행 (확인: `baseVersion=0.4.2, snapshotVersion=` in bluetape4k-graph/gradle.properties). 구현 전 아래 순서로 확인:
  1. `./gradlew -p /Users/debop/work/bluetape4k/bluetape4k-graph publishAllPublicationsToMavenLocalRepository` 로 0.4.2 로컬 발행 여부 확인
  2. 발행된 경우: `settings.gradle.kts` 에 `mavenLocal()` 추가 + `bluetape4k-graph = "0.4.2"` 사용
  3. 미발행인 경우: `bluetape4k-graph = "0.4.1"` 사용 (Maven Central 최신 확인 버전)
  4. 어느 경우든 TODO 주석 필수
- **작업**:
  ```toml
  [versions]
  # TODO: bluetape4k-dependencies BOM 이 bluetape4k-graph 를 govern 하면 pin 제거.
  # Version resolution: 0.4.2 로컬 빌드 발행 시 mavenLocal() 필요; 아니면 0.4.1 (last Maven Central).
  bluetape4k-graph = "0.4.1"   # or "0.4.2" if published to mavenLocal

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
- **작업**: spec §8 의 build.gradle.kts — BOM platform, core/tinkerpop impl, neo4j/memgraph `compileOnly` (NOT testImplementation — extendsFrom 이 테스트 classpath 로 전파), integrationTest task, `excludeTags("integration")` on default test task
- **⚠️ 중복 의존성 금지**: `compileOnly(libs.bluetape4k.graph.neo4j)` + `testImplementation(libs.bluetape4k.graph.neo4j)` 동시 선언 금지. `compileOnly` + `extendsFrom` 으로 충분.
- **⚠️ [P1-Arch-3] BOM platform import 가 반드시 첫 번째 dependency 여야 함** — version-less alias 들은 BOM 에서 버전을 받으므로 BOM import 가 먼저 선언되어야 한다:
  ```kotlin
  dependencies {
      implementation(platform(libs.bluetape4k.graph.bom))  // ← 반드시 첫 번째
      implementation(libs.bluetape4k.graph.core)
      implementation(libs.bluetape4k.graph.tinkerpop)
      compileOnly(libs.bluetape4k.graph.neo4j)
      compileOnly(libs.bluetape4k.graph.memgraph)
      // ...
  }
  ```
- **⚠️ [P1-Arch-3] Kotlin DSL `configurations {}` 에서 `.get()` 필수** — `NamedDomainObjectProvider<Configuration>` 는 `.extendsFrom()` 메서드가 없으므로 `.get()` 없이는 **compile error**. 반드시 아래 형식 사용:
  ```kotlin
  configurations {
      testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
  }
  ```
  참조: `redis/redisson-examples/build.gradle.kts:6`, `kotlin/coroutines/build.gradle.kts:6` — 동일 패턴.

### T1-4: 테스트 리소스 생성
- **complexity**: low
- **파일**:
  - `graph/abuser-detection/src/test/resources/junit-platform.properties`
  - `graph/abuser-detection/src/test/resources/logback-test.xml`
- **작업**:
  - `junit-platform.properties`: **⚠️ [P2-Arch-4] workshop 표준 6줄 전체 복사** — `junit.jupiter.execution.parallel.enabled=false` 만으로는 부족; 아래 전체 사용:
    ```properties
    junit.jupiter.extensions.autodetection.enabled=true
    junit.jupiter.testinstance.lifecycle.default=per_class

    junit.jupiter.execution.parallel.enabled=false
    junit.jupiter.execution.parallel.mode.default=same_thread
    junit.jupiter.execution.parallel.mode.classes.default=concurrent
    ```
    참조: `redis/redisson-examples/src/test/resources/junit-platform.properties`, `kotlin/coroutines` — 동일 6줄.  
    **`junit.jupiter.tags.exclude=integration` 절대 포함 금지** (JUnit 엔진 레벨 제외가 Gradle `integrationTest` 태스크도 막음; tag exclusion 은 `build.gradle.kts tasks.test { excludeTags }` 에서만)
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
  - **⚠️ Security KDoc 필수** — 민감 속성에 반드시 보안 주석 명기:
    ```kotlin
    /**
     * Vertex label for phone number identifiers.
     *
     * **Security**: `phone` property stores an **E.164-format hash** (SHA-256 hex or equivalent).
     * Raw phone numbers MUST NOT be persisted in the graph. Callers are responsible for hashing
     * before calling [AbuserDetectionService.addPhoneNumber].
     */
    object PhoneNumberLabel : VertexLabel { ... }

    /**
     * Vertex label for payment method identifiers.
     *
     * **Security**: `paymentToken` stores a **PCI-safe processor token** (e.g., Stripe/Braintree token),
     * never a raw PAN or CVV. Callers must obtain a tokenised reference from the payment processor
     * before calling [AbuserDetectionService.addPaymentMethod].
     */
    object PaymentMethodLabel : VertexLabel { ... }
    ```

---

## Phase 3 — Model Types (복잡도: low)

### T3-1: `IdentifierEdgeLabel.kt` — value class + companion constants
- **complexity**: low
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/model/IdentifierEdgeLabel.kt`
- **주의**: `model/` 디렉토리에 배치 (schema/ 아님) — `IdentifierEdgeLabel` 은 DB 스키마 선언이 아닌 도메인 수준 dispatch enum
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

## Phase 4 — Blocking Service (복잡도: high → 2개 sub-task로 분할)

### T4-1a: `AbuserDetectionService.kt` — mutators 구현 (CRUD operations)
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionService.kt`
- **작업**:
  - `initialize()`: `ops.graphExists` 체크 후 `ops.createGraph`; idempotent; safe to call multiple times
  - **findOrCreate mutators** — server-side filter 사용 (전체 스캔 금지):
    ```kotlin
    fun addDevice(deviceId: String, platform: String): GraphVertex {
        deviceId.requireNotBlank("deviceId")
        return ops.findVerticesByLabel(DeviceLabel.label, mapOf("deviceId" to deviceId))
            .firstOrNull()
            ?: ops.createVertex(DeviceLabel.label, mapOf("deviceId" to deviceId, "platform" to platform))
    }
    ```
  - `addUser`, `addIpAddress`, `addPhoneNumber`, `addPaymentMethod` 동일 패턴 (각 key 속성으로 서버 필터)
  - `linkDevice`, `linkIp`, `linkPhone`, `linkPayment`, `linkReferral` — `ops.createEdge` 호출
  - **KDoc 주의사항 (각 mutator)**: "Not safe for concurrent invocation; findOrCreate is not atomic. For atomic upsert, use mergeVertex."
  - 영문 KDoc 필수

### T4-1b: `AbuserDetectionService.kt` — query 알고리즘 구현
- **complexity**: high
- **파일**: 동일 파일 (T4-1a 와 같은 클래스)
- **선행**: T4-1a 완료 후 진행
- **작업**:
  - **`VERTEX_LABEL_TO_EDGE_LABEL` — companion object 에 선언** (함수 body 에 val 금지):
    ```kotlin
    companion object : KLogging() {
        private val VERTEX_LABEL_TO_EDGE_LABEL: Map<String, IdentifierEdgeLabel> = mapOf(
            "Device"        to IdentifierEdgeLabel.USES_DEVICE,
            "IpAddress"     to IdentifierEdgeLabel.USES_IP,
            "PhoneNumber"   to IdentifierEdgeLabel.HAS_PHONE,
            "PaymentMethod" to IdentifierEdgeLabel.USES_PAYMENT,
        )
    }
    ```
  - **`findAbuseCluster(seedUserId)`** — spec §6 label-dispatch 알고리즘:
    1. `ops.findVertexById(seedUserId) ?: return AbuseCluster(seedUserId, emptyList(), emptyList())` — 존재 여부 가드
    2. `IdentifierEdgeLabel.all.flatMap { edgeLabel -> ops.neighbors(seedUserId, NeighborOptions(edgeLabel.value, OUTGOING, 1)) }` → seedIdentifiers
    3. `seedIdentifiers.forEach { identifierVertex -> val matchingLabel = VERTEX_LABEL_TO_EDGE_LABEL[identifierVertex.label] ?: return@forEach ... }` → clusterUsers
  - **`explainSuspicion(userId)`**: `IdentifierEdgeLabel.all` 순회, `edgeLabel.value` String 전달
  - **`detectReferralLoops`**: `ops.detectCycles(CycleOptions(...))`
  - **`rankSuspiciousUsers`** (List 반환 — `mapIndexed` 사용 가능):
    ```kotlin
    ops.pageRank(PageRankOptions(...))
        .filter { it.vertex.label == UserLabel.label }
        .take(limit)
        .mapIndexed { idx, s -> SuspiciousUserScore(s.vertex, s.score, idx + 1) }
    ```
  - 영문 KDoc 필수

---

## Phase 5 — Suspend Service (복잡도: medium)

### T5-1: `AbuserDetectionSuspendService.kt` — suspend + Flow variants
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/service/AbuserDetectionSuspendService.kt`
- **선행**: T3-1, T3-2 완료
- **작업**:
  - `ops: GraphSuspendOperations` 사용
  - **findOrCreate — `Flow<GraphVertex>` 주의**: `GraphSuspendVertexRepository.findVerticesByLabel` 는 `Flow<GraphVertex>` 반환 (List 아님).
    ```kotlin
    suspend fun addDevice(deviceId: String, platform: String): GraphVertex {
        deviceId.requireNotBlank("deviceId")
        return ops.findVerticesByLabel(DeviceLabel.label, mapOf("deviceId" to deviceId))
            .firstOrNull()   // import kotlinx.coroutines.flow.firstOrNull  ← REQUIRED
            ?: ops.createVertex(DeviceLabel.label, mapOf("deviceId" to deviceId, "platform" to platform))
    }
    ```
  - `VERTEX_LABEL_TO_EDGE_LABEL` — companion object 에 선언 (T4-1b 동일)
  - **⚠️ [P0-Impl-2] `findAbuseCluster` suspend — `ops.neighbors()` 는 `Flow<GraphVertex>` 반환 (List 아님)**:
    `List.flatMap { Flow }` 는 compile error. `.toList()` 로 수집 후 flatMap 해야 함:
    ```kotlin
    suspend fun findAbuseCluster(seedUserId: GraphElementId): AbuseCluster {
        if (ops.findVertexById(seedUserId) == null) return AbuseCluster(seedUserId, emptyList(), emptyList())
        val seedIdentifiers: List<GraphVertex> = IdentifierEdgeLabel.all.flatMap { edgeLabel ->
            ops.neighbors(seedUserId, NeighborOptions(edgeLabel.value, OUTGOING, 1))
                .toList()   // import kotlinx.coroutines.flow.toList ← REQUIRED
        }
        // 이하 blocking 버전과 동일한 label-dispatch BFS 로직
    }
    ```
    **필수 imports**: `kotlinx.coroutines.flow.firstOrNull`, `kotlinx.coroutines.flow.toList`
  - **⚠️ [P1-Impl-1] `explainSuspicion` suspend — `ops.findEdgesByStartId()` 는 `Flow<GraphEdge>` 반환 (List 아님)**:
    `List` 방식 iteration 은 compile error. `flow { }` 빌더로 작성:
    ```kotlin
    fun explainSuspicion(userId: GraphElementId): Flow<AbusePath> = flow {
        userId.requireNotBlank("userId")    // ← validation inside flow{} — runs at collection time
        IdentifierEdgeLabel.all.forEach { lbl ->
            ops.findEdgesByStartId(userId, lbl.value).collect { edge ->
                emit(AbusePath(edge.endVertexId, lbl))
            }
        }
    }
    ```
    이 함수는 `suspend` 가 아닌 Flow-반환 함수. validation 은 collect 시 발생.
    테스트 패턴: `assertFailsWith<IllegalArgumentException> { service.explainSuspicion("").toList() }`
  - `rankSuspiciousUsers` — **`Flow.mapIndexed` 없음** — `.withIndex().map { (idx, s) -> SuspiciousUserScore(s.vertex, s.score, idx + 1) }` 사용.
    **`flow { }` 빌더 + `Flow.forEach` 패턴 금지 — `Flow.forEach` 는 존재하지 않음 (compile error).**
    Direct pipeline 으로 작성:
    ```kotlin
    fun rankSuspiciousUsers(limit: Int = 10): Flow<SuspiciousUserScore> =
        ops.pageRank(PageRankOptions(vertexLabel = null, edgeLabel = null, topK = Int.MAX_VALUE))
            .filter { it.vertex.label == UserLabel.label }
            .take(limit)
            .withIndex()
            .map { (idx, s) -> SuspiciousUserScore(s.vertex, s.score, idx + 1) }
    ```
  - Flow 수집 시 `CancellationException` 전파 (runCatching 내 suspend 호출 금지)
  - 영문 KDoc + Flow 수집 방법 설명 필수

---

## Phase 6 — Seed Data (복잡도: medium)

### T6-1: `AbuserDetectionSeed.kt` — 결정론적 테스트 픽스처
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/main/kotlin/io/bluetape4k/workshop/graph/abuser/seed/AbuserDetectionSeed.kt`
- **선행**: T4-1a, T4-1b, T5-1 완료
- **설계 전략**: `seedAll` 대신 **composable helpers** — 각 테스트가 `@BeforeEach cleanGraph` 후 필요한 helper 만 호출
  - `seedSharedIdentifiers(service): SeedResult` — 3 users × shared device+IP (test 2, 3, 5, 14); `unrelatedUser` 포함 (cluster 와 무관한 4번째 user 생성)
  - `seedReferralCycle(service): SeedResult` — A→B→C→A cycle (test 4)
  - `seedIsolatedUser(service): SeedResult` — **⚠️ [P1-Deliv-1] 반드시 cluster + isolated user 모두 생성해야 함**:
    - `user1`, `user2`, `user3` 는 `deviceA` 를 공유 (cluster 형성)
    - `unrelatedUser` 는 graph edge 없음 (완전 고립)
    - 이 구조가 없으면 test 7 에서 `seed.user1.id` NPE 발생
    - `phoneA`, `paymentA`, `referralUser*` = null
    - Test 7 assertions: `findAbuseCluster(seed.user1.id).users` 에 user2/user3 포함, `unrelatedUser` 미포함; `findAbuseCluster(seed.unrelatedUser.id).users.isEmpty()`
  - `seedAll(service): SeedResult` — 모두 포함 (backward compat, test 5)
- **suspend variant 필수**: blocking 버전과 동일한 suspend helpers:
  - `suspend fun seedSharedIdentifiers(service: AbuserDetectionSuspendService): SeedResult`
  - `suspend fun seedReferralCycle(service: AbuserDetectionSuspendService): SeedResult`
  - `suspend fun seedIsolatedUser(service: AbuserDetectionSuspendService): SeedResult`
  - `suspend fun seedAll(service: AbuserDetectionSuspendService): SeedResult`
- **`data class SeedResult` — 명시적 필드**:
  ```kotlin
  data class SeedResult(
      val user1: GraphVertex,    // test 2, 3, 5: seed user
      val user2: GraphVertex,    // test 2, 3, 5: shares device with user1
      val user3: GraphVertex,    // test 2, 3, 5: shares device with user1 and user2
      val unrelatedUser: GraphVertex,   // test 7: isolated user
      val deviceA: GraphVertex,  // shared device
      val ipA: GraphVertex,      // shared IP
      val phoneA: GraphVertex?,  // test 14: shared phone number (null if not seeded)
      val paymentA: GraphVertex?, // test 14: shared payment method (null if not seeded)
      val referralUserA: GraphVertex?, // test 4: cycle node A
      val referralUserB: GraphVertex?, // test 4: cycle node B
      val referralUserC: GraphVertex?, // test 4: cycle node C
  )
  ```
- 모든 timestamp 고정 (`"2026-01-01T00:00:00Z"`)
- 모든 identifier 는 placeholder hash

---

## Phase 7 — Abstract Test Base Blocking (복잡도: high)

### T7-1: `AbstractAbuserDetectionTest.kt`
- **complexity**: high
- **파일**: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionTest.kt`
- **작업**:
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 필수
  - `@BeforeEach cleanGraph()`: `runCatching { dropGraph }.onFailure { log.warn }` + `service.initialize()`
  - **각 테스트가 필요한 seed helper 직접 호출** (seedAll 를 @BeforeEach 에서 전역 호출 금지 — 일부 테스트는 빈 그래프 필요)
  - **15개 테스트 케이스** (happy 7 + failure 5 + additional 3):
    1. `creates user and links device` — `service.initialize()` 후 직접 addUser/addDevice/linkDevice (seed 없음)
    2. `finds shared-device abuse cluster — returns 2 other users (seed excluded)` — `seedSharedIdentifiers(service)` 호출; assert `findAbuseCluster(seed.user1.id).users shouldContainExactlyInAnyOrder listOf(seed.user2, seed.user3)`
    3. `explains suspicion by shared device and IP` — `seedSharedIdentifiers(service)` 사용:
       - **⚠️ [P1-Test-4] 구체 assertion 필수**: `service.explainSuspicion(seed.user1.id)` 결과에 대해:
         - `paths.shouldNotBeEmpty()`
         - `paths.map { it.edgeLabel }.toSet() shouldContainAll setOf(IdentifierEdgeLabel.USES_DEVICE, IdentifierEdgeLabel.USES_IP)`
    4. `detects referral loops (A→B→C→A)` — `seedReferralCycle(service)` 호출:
       - **⚠️ [P1-Test-4] 구체 assertion 필수**:
         - `val loops = service.detectReferralLoops()`
         - `loops.shouldNotBeEmpty()`
         - `loops.any { cycle -> cycle.vertices.map { it.id }.containsAll(listOf(seed.referralUserA!!.id, seed.referralUserB!!.id, seed.referralUserC!!.id)) } shouldBe true`
    5. `ranks user at center of identifier sharing as most suspicious` — `seedSharedIdentifiers(service)` 사용:
       - **⚠️ [P1-Test-4] 구체 assertion 필수**:
         - `val ranks = service.rankSuspiciousUsers()`
         - `ranks.shouldNotBeEmpty()`
         - `ranks.first().user.id shouldBeEqualTo seed.user1.id` — user1 이 device+IP 를 2명과 공유하므로 최고 score
    6. `empty graph returns empty cluster` — seed 없음; `findAbuseCluster(nonExistentId).users.isEmpty()`
    7. `cluster excludes unrelated users` — `seedIsolatedUser(service)` 호출 후:
       - `findAbuseCluster(seed.user1.id).users` 에 `seed.unrelatedUser` 포함 안 됨 확인
       - `findAbuseCluster(seed.unrelatedUser.id).users.isEmpty()` 확인 (isolated user 시드 시 cluster 비어 있어야 함)
    8. `addDevice with blank deviceId throws IllegalArgumentException`
    9. `addUser with blank userId throws IllegalArgumentException`
    10. `findAbuseCluster with non-existent seedUserId returns empty cluster`
    11. `rankSuspiciousUsers returns empty list on empty graph`
    12. `detectReferralLoops returns empty list when no REFERRED_BY edges exist`
    13. `addDevice called twice with same deviceId returns the same vertex id` — `service.addDevice("device-X", "ios").id == service.addDevice("device-X", "ios").id`; `ops.findVerticesByLabel(DeviceLabel.label).count { it.properties["deviceId"] == "device-X" } == 1`
    14. `shared phone and payment method detection` — seed two users sharing phone + payment:
        - **⚠️ [P1-Test-4] 구체 assertion 필수**:
          - `val cluster = service.findAbuseCluster(seed.user1.id)`
          - `cluster.users.size shouldBeGreaterThanOrEqual 1`
          - `cluster.sharedIdentifiers.map { it.label } shouldContainAll listOf(PhoneNumberLabel.label, PaymentMethodLabel.label)`
          - PhoneNumber + PaymentMethod 양쪽 label 이 모두 포함되어야 `HAS_PHONE`/`USES_PAYMENT` dispatch 분기 검증 완료
    15. `cluster excludes REFERRED_BY-only reachable users` — link userA→userB via USES_DEVICE + REFERRED_BY; link userC→userA via REFERRED_BY only; assert userB in cluster, userC NOT in cluster
  - Exception tests: `assertFailsWith<IllegalArgumentException> { }`
  - Assertion: `shouldBe`, `shouldContainExactlyInAnyOrder` (bluetape4k-assertions)
  - 백틱 테스트 이름 사용

---

## Phase 8 — Abstract Test Base Suspend (복잡도: medium)

### T8-1: `AbstractAbuserDetectionSuspendTest.kt`
- **complexity**: medium
- **파일**: `graph/abuser-detection/src/test/kotlin/io/bluetape4k/workshop/graph/abuser/AbstractAbuserDetectionSuspendTest.kt`
- **선행**: T7-1 완료 (동일 15개 테스트 구조 참조)
- **작업**:
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 필수
  - **⚠️ [P1-Test-1] `@BeforeEach cleanGraph()` — suspend call 은 `runCatching {}` 금지**:
    `runCatching {}` 안에서 suspend 함수 호출 시 `CancellationException` 삼킴 (CLAUDE.md 규칙 위반).
    올바른 패턴:
    ```kotlin
    @BeforeEach
    fun cleanGraph() = runTest {
        try {
            if (ops.graphExists(graphName)) ops.dropGraph(graphName)
        } catch (e: CancellationException) {
            throw e   // ← 반드시 재throw
        } catch (e: Exception) {
            log.warn(e) { "dropGraph failed; continuing with initialize()" }
        }
        suspendService.initialize()
    }
    ```
    `@BeforeEach fun cleanGraph() = runTest { ... }` 형식 사용 — JUnit 5 가 `runTest` 의 반환값 무시함으로 정상 동작.
  - 동일 15개 테스트를 `runTest { }` 로 래핑
  - **suspend seeder 사용**: `seedSharedIdentifiers(suspendService)`, `seedReferralCycle(suspendService)`, `seedIsolatedUser(suspendService)` (T6-1 suspend 버전)
  - Flow 테스트: `explainSuspicion(...).toList()`, `rankSuspiciousUsers(...).toList()`
  - **Flow exception 테스트 패턴 주의**: `explainSuspicion` 는 non-suspend Flow — validation 은 collect 시 발생
    ```kotlin
    // ✅ CORRECT
    runTest {
        assertFailsWith<IllegalArgumentException> {
            service.explainSuspicion(GraphElementId("")).toList()
        }
    }
    // ❌ WRONG (validation not triggered — flow not collected)
    // coInvoking { service.explainSuspicion(GraphElementId("")) } shouldThrow IllegalArgumentException::class
    ```
  - **suspend `fun` exception 테스트**: `coInvoking { service.addDevice("", "ios") } shouldThrow IllegalArgumentException::class`
  - **Flow 취소 검증 테스트** (test 16): `rankSuspiciousUsers` Flow 를 collect 중인 Job 을 cancel 하고 취소 전파 확인
    - **⚠️ [P1-Test-2] empty graph 에서는 Flow 가 즉시 완료 → cancel 이 도달하기 전에 정상 종료 → silent pass**
      반드시 seed data 를 넣고 slow consumer 사용해야 cancellation 이 in-flight 중에 도달함:
    ```kotlin
    // ✅ Option A — seed data 필수 + slow consumer + async/deferred.await()
    runTest {
        seedSharedIdentifiers(suspendService)   // graph data 필수 — PageRank 가 실제 emit 해야 함
        val deferred = async {
            service.rankSuspiciousUsers()
                .onEach { delay(10) }           // slow consumer — cancel 이 in-flight 중에 도달하도록
                .collect { }
        }
        deferred.cancel()
        assertFailsWith<CancellationException> { deferred.await() }
    }

    // ✅ Option B — backgroundScope (isCancelled 상태 검증)
    runTest {
        seedSharedIdentifiers(suspendService)
        val job = backgroundScope.launch {
            service.rankSuspiciousUsers().onEach { delay(10) }.collect { }
        }
        job.cancel()
        job.join()
        job.isCancelled shouldBe true
    }
    ```
    **⚠️ 금지 패턴 — compile 통과하나 런타임 오류**:
    ```kotlin
    // ❌ WRONG — job.join() 은 취소된 Job 에서 CancellationException 를 throw 하지 않음
    assertFailsWith<CancellationException> { job.join() }

    // ❌ WRONG — CoroutineScope(Job()) 은 runTest dispatcher 외부 scope; 테스트 종료 후에도 살아있는 coroutine leak
    val scope = CoroutineScope(Job())
    scope.launch { ... }
    ```
  - `CancellationException` 재throw 필수 (runCatching 내 suspend 호출 금지)

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
  - `graphName = "abuser_tinkergraph"` — TinkerGraph 에서는 graphName 이 cosmetic (실제 graph 분리 없음; `dropGraph` 는 단일 in-memory graph 전체 초기화)
  - `AbuserDetectionService(ops, graphName)` / `AbuserDetectionSuspendService(ops, graphName)` 생성
  - `AbstractAbuserDetectionTest` / `AbstractAbuserDetectionSuspendTest` 확장

---

## Phase 10 — Integration Tests (복잡도: medium)

### T10-1: `AbuserDetectionNeo4jTest.kt`
- **complexity**: medium
- **파일**: `.../AbuserDetectionNeo4jTest.kt`
- **작업**:
  - `@Tag("integration")`
  - **Driver wiring skeleton**:
    ```kotlin
    companion object : KLogging() {
        val neo4j = Neo4jServer.Launcher.neo4j  // bluetape4k-testcontainers launcher
        val driver: Driver = GraphDatabase.driver(neo4j.boltUrl, AuthTokens.none())
    }
    // ⚠️ graphName MUST be declared before ops — Kotlin initializes properties in declaration order.
    // Declaring ops first would read graphName before it is initialized (null → NPE or wrong value).
    override val graphName = "abuser_neo4j"
    // ⚠️ [P0-Impl-1] Neo4jGraphOperations 생성자 2번째 인자는 `database: String = "neo4j"` (Bolt database 이름).
    // graphName ("abuser_neo4j") 를 여기 전달하면 존재하지 않는 database 에 연결 → ClientException: Database does not exist.
    // graphName 은 서비스 생성자 (abstract base 에서 AbuserDetectionService(ops, graphName)) 에 전달되어
    // createGraph(graphName) / dropGraph(graphName) 에서만 사용됨 — 생성자에는 전달하지 않음.
    override val ops: GraphOperations = Neo4jGraphOperations(driver)   // default database = "neo4j"
    ```
  - `@AfterAll fun teardown()`: `runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }.onFailure { log.warn }` + `driver.close()`

### T10-2: `AbuserDetectionMemgraphTest.kt`
- **complexity**: medium
- **파일**: `.../AbuserDetectionMemgraphTest.kt`
- **작업**:
  - **`@Tag("integration")` 필수** — default `test` task 에서 제외, `integrationTest` task 에서만 실행
  - T10-1 과 동일 패턴, `MemgraphServer.Launcher.memgraph`
  - `graphName = "abuser_memgraph"`
  - **Driver wiring skeleton**:
    ```kotlin
    @Tag("integration")
    class AbuserDetectionMemgraphTest : AbstractAbuserDetectionTest() {
        companion object : KLogging() {
            val memgraph = MemgraphServer.Launcher.memgraph
            val driver: Driver = GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())
        }
        // ⚠️ graphName MUST be declared before ops (same initialization order rule as T10-1)
        override val graphName = "abuser_memgraph"
        // ⚠️ [P0-Impl-1] MemgraphGraphOperations 생성자 2번째 인자는 `database: String = "memgraph"`.
        // graphName 전달 금지 — default database "memgraph" 사용.
        override val ops: GraphOperations = MemgraphGraphOperations(driver)   // default database = "memgraph"

        @AfterAll
        fun teardown() {
            runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }.onFailure { log.warn(it) { "dropGraph failed" } }
            driver.close()
        }
    }
    ```

### T10-3: `AbuserDetectionSuspendNeo4jTest.kt`
- **complexity**: medium
- **파일**: `.../AbuserDetectionSuspendNeo4jTest.kt`
- **작업**:
  - `@Tag("integration")`
  - T10-1 와 동일 container + driver wiring
  - **⚠️ 초기화 순서**: `override val graphName = "abuser_suspend_neo4j"` 를 `override val ops` **앞에** 선언
  - `override val ops: GraphSuspendOperations = Neo4jGraphSuspendOperations(driver, graphName)`
  - `graphName = "abuser_suspend_neo4j"` (T10-1 와 다른 unique name — DB 충돌 방지)
  - `AbstractAbuserDetectionSuspendTest` 확장
  - **`@AfterAll fun teardown()` 필수** — driver 반납:
    ```kotlin
    @AfterAll
    fun teardown() {
        runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }.onFailure { log.warn(it) { "dropGraph failed" } }
        driver.close()
    }
    ```
  - **Driver wiring skeleton**:
    ```kotlin
    @Tag("integration")
    class AbuserDetectionSuspendNeo4jTest : AbstractAbuserDetectionSuspendTest() {
        companion object : KLogging() {
            val neo4j = Neo4jServer.Launcher.neo4j
            val driver: Driver = GraphDatabase.driver(neo4j.boltUrl, AuthTokens.none())
        }
        override val graphName = "abuser_suspend_neo4j"
        // ⚠️ [P1-Impl-3] Neo4jGraphSuspendOperations 생성자 2번째 인자는 `database: String = "neo4j"`.
        // graphName 전달 금지 — default database "neo4j" 사용.
        override val ops: GraphSuspendOperations = Neo4jGraphSuspendOperations(driver)   // default database = "neo4j"

        @AfterAll
        fun teardown() {
            runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }.onFailure { log.warn(it) { "dropGraph failed" } }
            driver.close()
        }
    }
    ```

### T10-4: `AbuserDetectionSuspendMemgraphTest.kt`
- **complexity**: medium
- **파일**: `.../AbuserDetectionSuspendMemgraphTest.kt`
- **작업**:
  - **`@Tag("integration")` 필수** — default `test` task 에서 제외, `integrationTest` task 에서만 실행
  - T10-3 패턴, `MemgraphServer.Launcher.memgraph`
  - **⚠️ 초기화 순서**: `override val graphName = "abuser_suspend_memgraph"` 를 `override val ops` **앞에** 선언
  - `graphName = "abuser_suspend_memgraph"`
  - **`@AfterAll fun teardown()` 필수** — driver 반납:
  - **Driver wiring skeleton**:
    ```kotlin
    @Tag("integration")
    class AbuserDetectionSuspendMemgraphTest : AbstractAbuserDetectionSuspendTest() {
        companion object : KLogging() {
            val memgraph = MemgraphServer.Launcher.memgraph
            val driver: Driver = GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())
        }
        override val graphName = "abuser_suspend_memgraph"
        // ⚠️ [P1-Impl-3] MemgraphGraphSuspendOperations 생성자 2번째 인자는 `database: String = "memgraph"`.
        // graphName 전달 금지 — default database "memgraph" 사용.
        override val ops: GraphSuspendOperations = MemgraphGraphSuspendOperations(driver)   // default database = "memgraph"

        @AfterAll
        fun teardown() {
            runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }.onFailure { log.warn(it) { "dropGraph failed" } }
            driver.close()
        }
    }
    ```

---

## Phase 11 — README (복잡도: low)

### T11-0: `Skill("bluetape4k-diagram")` 명시적 호출 (README 작성 전 필수)
- **complexity**: low
- **작업**:
  - `Skill("bluetape4k-diagram")` 를 **T11-1 시작 전** 반드시 호출
  - skill 미사용 시 raw SVG/Mermaid 작성 금지 — 오케스트레이터에 escalate
  - skill 호출 후 diagram 가이드라인에 따라 아키텍처 다이어그램 준비

### T11-1: `README.md` + `README.ko.md`
- **complexity**: low
- **파일**:
  - `graph/abuser-detection/README.md`
  - `graph/abuser-detection/README.ko.md`
- **선행**: T11-0 (`Skill("bluetape4k-diagram")`) 완료 후
- **작업**:
  - Architecture diagram 포함 (spec §2 참조)
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

### T12-3: Lessons 문서 작성 + 커밋 (**PR 생성 전 반드시 커밋 완료**)
- **complexity**: low
- **파일**: `docs/lessons/2026-05-25-graph-abuser-detection.md`
- **타이밍**: Step 7-P (PR 생성) 전에 이 파일을 반드시 feature branch 에 커밋; merge 후 작성하면 별도 PR 필요
- **작업**: root cause, decision, outcome, review misses (label-dispatch bug, Flow API 차이, findOrCreate 전략), future guidance

### T12-4: bluetape4k-patterns checklist 검증
- **complexity**: low
- **작업**:
  - `Skill("bluetape4k-patterns")` 호출하여 패턴 체크리스트 실행
  - Kotlin 패턴: `requireNotBlank`, `CancellationException`, `@TestInstance`, KDoc
  - 테스트 패턴: `assertFailsWith`, `runTest`, Flow 취소, idempotency
  - Testcontainers: launcher singleton 사용 확인

---

## 실행 순서 (병렬 가능 그룹)

```
P1 (T1-1 ~ T1-4)    → 순차 (서로 의존)
P2 (T2-1)            → P1 완료 후
P3 (T3-1, T3-2)      → P2 완료 후 (병렬 가능)
P4a (T4-1a)          → P3 완료 후 (medium complexity)
P4b (T4-1b)          → T4-1a 완료 후 (high complexity — Opus)
P5 (T5-1)            → P3 완료 후 (P4a 와 병렬 가능; P4b 완료 전에 진행)
P6 (T6-1)            → P4b, P5 완료 후 (blocking + suspend seeders 모두 필요)
P7 (T7-1)            → P6 완료 후 (high complexity — Opus)
P8 (T8-1)            → P6 완료 후 (P7 과 병렬 가능)
P9 (T9-1)            → P7, P8 완료 후
P10 (T10-1~T10-4)    → P7, P8 완료 후 (P9 와 병렬 가능)
P11 (T11-0, T11-1)   → 코드 완성 후 (P9 이후); T11-0 먼저
P12 (T12-1~T12-4)    → 전체 완료 후
```

---

## DoD 체크리스트

- [ ] T1-1: `settings.gradle.kts` — `graph` 모듈 `graalvm` 뒤에 삽입
- [ ] T1-2: `libs.versions.toml` — version resolution 완료 (mavenLocal/0.4.1/0.4.2 결정)
- [ ] T1-3: `build.gradle.kts` — BOM + compileOnly + integrationTest task (중복 testImplementation 없음)
- [ ] T1-4: `junit-platform.properties` — tags.exclude 없음; `logback-test.xml`
- [ ] T2-1: AbuserDetectionSchema.kt — 5 vertex + 5 edge labels
- [ ] T3-1~T3-2: 모델 타입 4종 (IdentifierEdgeLabel, AbuseCluster, AbusePath, SuspiciousUserScore)
- [ ] T4-1a: AbuserDetectionService mutators — server-side filter findOrCreate
- [ ] T4-1b: AbuserDetectionService queries — VERTEX_LABEL_TO_EDGE_LABEL companion + findVertexById guard + label-dispatch BFS
- [ ] T5-1: AbuserDetectionSuspendService — Flow.firstOrNull + withIndex rank + Flow cancellation safe
- [ ] T6-1: AbuserDetectionSeed — composable helpers + blocking + suspend variants + SeedResult 명시 필드
- [ ] T7-1: AbstractAbuserDetectionTest — 15 test cases (7 happy + 5 failure + 3 additional)
- [ ] T8-1: AbstractAbuserDetectionSuspendTest — 16 test cases (15 + Flow cancellation) + Flow exception 패턴 정확
- [ ] T9-1: TinkerGraph 구상 테스트 2종
- [ ] T10-1~T10-4: Neo4j + Memgraph integration 테스트 4종 (blocking + suspend per backend)
- [ ] T11-0: `Skill("bluetape4k-diagram")` 호출 완료
- [ ] T11-1: README.md + README.ko.md + diagram
- [ ] T12-1: `./gradlew :graph-abuser-detection:test` 전체 통과
- [ ] T12-2: CI workflow 누락 없음
- [ ] T12-3: Lessons 문서 커밋 (PR 생성 전)
- [ ] T12-4: bluetape4k-patterns checklist 통과

---

## Plan Review Iteration Log

| Round | Reviewer | P0 | P1 | P2 | P3 | Applied |
|-------|----------|----|----|----|----|---------|
| Round 1 (Step 3-R) | 3r-delivery (haiku) | 0 | 3 | 0 | 0 | v2: T12-3 timing, T11-0 추가, T12-4 추가 |
| Round 1 (Step 3-R) | 3r-tester (sonnet) | 0 | 7 | 3 | 0 | v2: test 13/14/15 추가; Flow exception 패턴; T10-3/T10-4 추가; T1-4 tags.exclude 제거; DoD count 수정 |
| Round 1 (Step 3-R) | 3r-implementer (sonnet) | 0 | 3 | 3 | 3 | v2: T4-1a/b 분할; suspend seeder 추가; version 주의사항; T1-1 삽입 위치 수정 |
| Round 1 (Step 3-R) | 3r-architect (sonnet) | 0 | 6 | 3 | 0 | v2: Flow firstOrNull; withIndex; server-side filter; seedAll composable; companion hoist; driver wiring; TOCTOU note; findVertexById guard |
| **Round 1 total** | **all 4 agents** | **0** | **19** | **9** | **3** | **plan v2 반영 중** |

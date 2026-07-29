# 디자인 사양 — graph/abuser-detection 워크샵 모듈

**날짜**: 2026-05-25
**문제**: [#12](https://github.com/bluetape4k/bluetape4k-workshop/issues/12)
**상태**: 초안
**작성자**: AI-지원 (Claude)

---

## 1. 이슈 설명

Issue #12에는 장치, IP 주소, 전화번호, 결제 방법과 같은 식별자를 공유하여 여러 계정을 운영하는 의심스러운 사용자를 찾는 프로세스인 **악용자 감지**를 보여주는 주요 `bluetape4k-graph` 워크숍 예제가 필요합니다.

기존 `bluetape4k-graph/examples/fraud-detection-examples/`은 금융 사기(동종 계정 정점 간의 자금 흐름 순환 이체)를 모델링합니다. 남용자 탐지 시나리오는 구조적으로 다릅니다. `User` 정점이 공유 식별자 가장자리를 통해 여러 식별자 정점 유형에 연결되는 **이기종 그래프**를 사용합니다. 악용자는 **전이적 공유 식별자 환경**(몇 번의 홉 내에서 도달할 수 있는 식별자를 공유하는 사용자)을 통해 드러납니다.

---

## 2. 목표

- 다중 계정 남용 감지를 위해 그래프 모델링이 SQL 조인보다 우수한 이유를 보여줍니다.
- bluetape4k-graph API 표시: vertex/edge CRUD, BFS 기반 클러스터 순회, 주기 감지, PageRank.
- Neo4j 및 Memgraph에 대한 인메모리(TinkerGraph) 빠른 테스트 경로와 옵트인 통합 테스트를 제공합니다.
- 동일한 도메인의 서비스 차단 및 정지 패턴을 보여줍니다.

---

## 3. 논골

- REST API 엔드포인트(이 모듈에는 Spring Boot 없음).
- AGE / FalkorDB 통합 테스트(지원되는 것으로 문서화되었지만 CI에서는 실행되지 않음).
- 실제 PII 데이터(모든 식별자는 hashed/tokenized 자리 표시자임)

---

## 4. 그래프 도메인 모델

### 4.1 정점 라벨

| 개체 | 라벨 | 주요 속성 |
|--------|-------|----------------|
| `UserLabel` | `"User"` | `userId`, `createdAt`, `signupCountry` |
| `DeviceLabel` | `"Device"` | `deviceId`, `platform` |
| `IpAddressLabel` | `"IpAddress"` | `ip`, `asn` |
| `PhoneNumberLabel` | `"PhoneNumber"` | `phone` (E.164 해시) |
| `PaymentMethodLabel` | `"PaymentMethod"` | `paymentToken` (PCI-안전한 토큰), `brand` |

### 4.2 가장자리 라벨

| 개체 | 라벨 | 출발 → 도착 | 주요 속성 |
|--------|-------|-----------|----------------|
| `UsesDeviceLabel` | `"USES_DEVICE"` | 사용자 → 장치 | `firstSeenAt` |
| `UsesIpLabel` | `"USES_IP"` | 사용자 → IpAddress | `firstSeenAt` |
| `HasPhoneLabel` | `"HAS_PHONE"` | 사용자 → PhoneNumber | `verifiedAt` |
| `UsesPaymentLabel` | `"USES_PAYMENT"` | 사용자 → PaymentMethod | `firstChargedAt` |
| `ReferredByLabel` | `"REFERRED_BY"` | 사용자 → 사용자 | `occurredAt` |

### 4.3 남용 감지 논리

- **악용 클러스터**: **직접 공유 식별자 홉**(사용자 → 식별자 → 사용자, 정확히 1개의 식별자 수준)을 통해 시드 사용자로부터 도달할 수 있는 사용자 집합입니다. `REFERRED_BY` 에지는 클러스터 순회에서 제외됩니다.
- **의심 설명**: 각 식별자 엣지 라벨에 대해 사용자와 연결된 식별자를 찾고 해당 식별자를 공유하는 다른 사용자를 반환합니다.
- **참조 루프**: `REFERRED_BY` 에지에서 주기 감지(보상 파밍 감지).
- **의심스러운 순위**: 전체 그래프에 대한 PageRank; 사용자 정점에 대한 사후 필터(식별자-브리지 토폴로지는 밀집된 공유 클러스터에서 사용자 점수를 증폭시킵니다).

> **참고**: `findAbuseCluster`은 의도적으로 고정된 1홉 식별자 순회(사용자→식별자→사용자 = 2개의 에지 홉)를 구현합니다. 이는 직접 공유 감지에 충분하며 워크샵 설정에서 무제한 순회를 방지합니다.

---

## 5. API 디자인

### 5.1 AbuserDetectionService (차단)

```kotlin
class AbuserDetectionService(
    private val ops: GraphOperations,
    private val graphName: String = "abuser_detection",
) {
    companion object : KLogging()

    /**
     * Creates the backing graph when it does not already exist. No-op if graph exists.
     * **Must be called before any mutator.** All mutators throw [IllegalStateException]
     * if called on an uninitialized service. Safe to call multiple times (idempotent).
     * Implementation: checks `ops.graphExists(graphName)` before `ops.createGraph(graphName)`.
     * Under `junit.jupiter.execution.parallel.enabled=false`, concurrent initialization is not a concern.
     */
    fun initialize()                              // idempotent; createGraph if absent

    // Mutators — identifier vertex creators use findOrCreate semantics:
    // If a vertex with the same key property already exists, return the existing vertex.
    // This is critical for shared-identifier detection: two users sharing "device-X"
    // must link to the SAME Device vertex, not two separate vertices.
    //
    // findOrCreate lookup: ops.findVerticesByLabel(DeviceLabel.label, mapOf("deviceId" to deviceId)).firstOrNull()
    // before creating; if found, return existing vertex.
    //
    // findOrCreate contract — "first-create wins":
    //   • The KEY property (deviceId / ip / phone / paymentToken) identifies uniqueness.
    //   • If the vertex already exists (matched by key), the EXISTING vertex is returned as-is.
    //   • Secondary/mutable properties (platform, asn, brand) from the second call are IGNORED —
    //     they are NOT merged into the existing vertex.
    //   • Callers must not assume that repeated calls with different secondary props update the vertex.
    //   • This is NOT atomic (TOCTOU race under parallel invocation); concurrent callers may
    //     create duplicate vertices. The service is designed for sequential use (PER_CLASS test instances,
    //     `junit.jupiter.execution.parallel.enabled=false`).
    //
    // Timestamp defaults: passing "" for createdAt/firstSeenAt/verifiedAt/firstChargedAt/occurredAt
    // stores an empty string in the graph. Callers MUST pass a valid ISO-8601 timestamp string
    // (e.g. Instant.now().toString()) when the field is meaningful.
    // In tests, use a fixed string like "2026-01-01T00:00:00Z" for determinism.
    //
    // Hash validation: deviceId, ip, phone, paymentToken parameters MUST be non-blank.
    // Callers are responsible for passing hashed/tokenized values (see §10).
    // Implementation calls requireNotBlank("deviceId") etc. before vertex lookup.
    fun addUser(userId: String, country: String, createdAt: String = ""): GraphVertex
    fun addDevice(deviceId: String, platform: String): GraphVertex          // findOrCreate by deviceId
    fun addIpAddress(ip: String, asn: String = ""): GraphVertex             // findOrCreate by ip
    fun addPhoneNumber(phone: String): GraphVertex                          // findOrCreate by phone
    fun addPaymentMethod(paymentToken: String, brand: String): GraphVertex  // findOrCreate by paymentToken

    fun linkDevice(userVertexId: GraphElementId, deviceVertexId: GraphElementId, firstSeenAt: String = "")
    fun linkIp(userVertexId: GraphElementId, ipVertexId: GraphElementId, firstSeenAt: String = "")
    fun linkPhone(userVertexId: GraphElementId, phoneVertexId: GraphElementId, verifiedAt: String = "")
    fun linkPayment(userVertexId: GraphElementId, paymentVertexId: GraphElementId, firstChargedAt: String = "")
    fun linkReferral(referrerVertexId: GraphElementId, referredVertexId: GraphElementId, occurredAt: String = "")

    // Queries
    // findAbuseCluster: fixed 1-hop identifier traversal (User→Identifier→User).
    // Returns AbuseCluster(users = emptyList(), sharedIdentifiers = emptyList()) when
    // seedUserId has no identifier links. Never throws for a valid (even non-existent) seedUserId.
    fun findAbuseCluster(seedUserId: GraphElementId): AbuseCluster
    fun explainSuspicion(userId: GraphElementId): List<AbusePath>
    fun detectReferralLoops(maxDepth: Int = 6, maxCycles: Int = 20): List<GraphCycle>
    fun rankSuspiciousUsers(limit: Int = 10): List<SuspiciousUserScore>
}
```

> **백엔드별 그래프 이름 격리**: 각 구체적인 통합 테스트 클래스는 고유한 이름을 제공해야 합니다.
> `graphName` ~ `AbuserDetectionService`(예: `"abuser_neo4j"`, `"abuser_memgraph"`) ~
> 한 클래스의 `dropGraph`이 다른 클래스의 그래프 설정으로 경주하는 것을 방지합니다.
> 동일한 JVM에서 동시에 실행됩니다.

### 5.2 AbuserDetectionSuspendService (코루틴)

```kotlin
class AbuserDetectionSuspendService(
    private val ops: GraphSuspendOperations,
    private val graphName: String = "abuser_detection",
) {
    companion object : KLoggingChannel()

    // All mutators are suspend
    suspend fun initialize()
    suspend fun addUser(userId: String, country: String, createdAt: String = ""): GraphVertex
    // ... same mutator shape as blocking

    // Streaming queries — Flow-returning methods produce cold flows.
    // The caller is responsible for collecting them within an appropriate coroutine scope.
    // Cancellation: Flow collection honours structured concurrency; cancelling the collecting
    // coroutine causes the flow to terminate cleanly at the next suspension point.
    // Callers must NOT use GlobalScope for collection; use viewModelScope, lifecycleScope, or
    // a test-scoped coroutineScope / runTest block.
    suspend fun findAbuseCluster(seedUserId: GraphElementId): AbuseCluster
    fun explainSuspicion(userId: GraphElementId): Flow<AbusePath>
    fun detectReferralLoops(maxDepth: Int = 6, maxCycles: Int = 20): Flow<GraphCycle>
    fun rankSuspiciousUsers(limit: Int = 10): Flow<SuspiciousUserScore>
}
```

### 5.3 결과 유형

```kotlin
/**
 * Value class representing one of the four shared-identifier edge labels.
 * Use the predefined constants — do not construct with arbitrary strings.
 */
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

/**
 * A cluster of users that share at least one identifier with the seed user.
 *
 * - [seedUserId]: the root user ID provided to [AbuserDetectionService.findAbuseCluster].
 * - [users]: users reachable from the seed via shared identifiers, **excluding the seed user itself**.
 * - [sharedIdentifiers]: identifier vertices (Device, IpAddress, PhoneNumber, PaymentMethod) acting as bridges.
 */
data class AbuseCluster(
    val seedUserId: GraphElementId,
    /** Cluster members, **not including** the seed user. */
    val users: List<GraphVertex>,
    /** Identifier vertices (Device, IpAddress, etc.) connecting the cluster. */
    val sharedIdentifiers: List<GraphVertex>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * A single evidence path: [fromUserId] and [toUserId] share [sharedIdentifier] via [edgeLabel].
 *
 * [edgeLabel] is always one of the four identifier edge labels ([IdentifierEdgeLabel]).
 * It is never [ReferredByLabel] ("REFERRED_BY") — that is detected separately via [detectReferralLoops].
 */
data class AbusePath(
    val fromUserId: GraphElementId,
    val toUserId: GraphElementId,
    val sharedIdentifier: GraphVertex,
    /** One of the four identifier edge labels — always an [IdentifierEdgeLabel] constant. */
    val edgeLabel: IdentifierEdgeLabel,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class SuspiciousUserScore(
    val user: GraphVertex,
    val score: Double,
    val rank: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

---

## 6. 알고리즘 구현 노트

### `findAbuseCluster(seedUserId)`

> **중요**: NOT `edgeLabel = null`을 사용하세요. 이렇게 하면 `REFERRED_BY` 가장자리를 통과하게 됩니다.
> 관련 없는 사용자를 클러스터로 끌어옵니다. 식별자 모서리 라벨만 사용하세요.
>
> **`IdentifierEdgeLabel` 대 `String`**: `ops` API (`NeighborOptions`, `findEdgesByStartId`)
> `edgeLabel`에 대한 원시 `String`을 기대합니다. `edgeLabel.value` 통과(`IdentifierEdgeLabel` 아님)
> 직접 반대) 모든 통화 현장에서.

1홉 식별자 순회 수정(`User → Identifier → User`):

```kotlin
val visited = mutableSetOf<GraphElementId>()
val identifierVertices = mutableListOf<GraphVertex>()
val clusterUsers = mutableListOf<GraphVertex>()

// Step 1: hop out from seed user to identifier vertices (label-dispatched — each label
// queries only the edges relevant to that identifier type)
val seedIdentifiers = IdentifierEdgeLabel.all.flatMap { edgeLabel ->
    ops.neighbors(seedUserId, NeighborOptions(edgeLabel = edgeLabel.value, direction = OUTGOING, maxDepth = 1))
}
identifierVertices += seedIdentifiers

// Vertex-label → edge-label mapping (vertex labels are NOT the same strings as edge labels)
val vertexLabelToEdgeLabel: Map<String, IdentifierEdgeLabel> = mapOf(
    "Device"        to IdentifierEdgeLabel.USES_DEVICE,
    "IpAddress"     to IdentifierEdgeLabel.USES_IP,
    "PhoneNumber"   to IdentifierEdgeLabel.HAS_PHONE,
    "PaymentMethod" to IdentifierEdgeLabel.USES_PAYMENT,
)

// Step 2: for each identifier vertex, find users connected via its corresponding edge label.
// Do NOT compare identifierVertex.label.uppercase() to edge label strings — they differ
// (e.g. "Device" vs "USES_DEVICE"). Use the explicit map above.
seedIdentifiers.forEach { identifierVertex ->
    val matchingLabel = vertexLabelToEdgeLabel[identifierVertex.label]
        ?: return@forEach  // skip unrecognized vertex types

    val connectedUsers = ops.neighbors(
        identifierVertex.id,
        NeighborOptions(edgeLabel = matchingLabel.value, direction = INCOMING, maxDepth = 1)
    ).filter { it.id != seedUserId && it.id !in visited && it.label == UserLabel.label }
    clusterUsers += connectedUsers
    visited += connectedUsers.map { it.id }
}

→ AbuseCluster(seedUserId, users = clusterUsers.distinct(), sharedIdentifiers = identifierVertices.distinct())
```

> **라벨 디스패치 최적화**: 식별자 꼭짓점당 4개의 모서리 라벨을 모두 쿼리하는 대신
> (O(4N) ​​왕복), 알고리즘은 다음과 같이 식별자 꼭짓점당 정확히 1개의 쿼리를 전달합니다.
> 정점의 자체 레이블을 해당 가장자리 레이블과 일치시킵니다. 이는 O(N) 쿼리로 줄어듭니다.

시드에 식별자 링크가 없거나 seedUserId이 존재하지 않는 경우 `AbuseCluster(users = emptyList(), sharedIdentifiers = emptyList())`를 반환합니다. 예외는 없습니다.

### `explainSuspicion(userId)`
`IdentifierEdgeLabel.all`(`USES_DEVICE`, `USES_IP`, `HAS_PHONE`, `USES_PAYMENT`)의 각 `edgeLabel`에 대해 다음을 수행합니다.
1. `ops.findEdgesByStartId(userId, edgeLabel.value)` → 가장자리에서 식별자 정점으로
2. 각 식별자 가장자리에 대해: `ops.neighbors(identifierId, NeighborOptions(edgeLabel.value, INCOMING, maxDepth=1))`
3. `userId` 자체를 필터링합니다. 발견된 다른 사용자당 `AbusePath(userId, otherUserId, identifierVertex, edgeLabel)` 방출

### `detectReferralLoops(maxDepth, maxCycles)`
```
ops.detectCycles(CycleOptions(
    vertexLabel = UserLabel.label,
    edgeLabel = ReferredByLabel.label,
    maxDepth = maxDepth,
    maxCycles = maxCycles
))
```

### `rankSuspiciousUsers(limit)`
```
ops.pageRank(PageRankOptions(vertexLabel = null, edgeLabel = null, topK = Int.MAX_VALUE))
  → filter { it.vertex.label == UserLabel.label }
  → take(limit)
  → withIndex().map { (idx, s) -> SuspiciousUserScore(s.vertex, s.score, idx + 1) }
```

> **`Flow.mapIndexed`이(가) `kotlinx.coroutines.flow`에 존재하지 않습니다**. `AbuserDetectionSuspendService`에서는
> 일시 중지 `rankSuspiciousUsers`은 `Flow<SuspiciousUserScore>`을 반환합니다. `.withIndex().map { (idx, s) -> ... }` 사용
> (Flow `mapIndexed`과 유사) 또는 `var rank = 1` 카운터로 누적됩니다.
> 차단 `AbuserDetectionService.rankSuspiciousUsers`은 `List<SuspiciousUserScore>`을 반환하고
> `mapIndexed`을 직접 사용할 수 있습니다.

---

## 7. 모듈 구조

```
bluetape4k-workshop/
├── settings.gradle.kts           ← add: includeModules("graph", false, true)
├── gradle/libs.versions.toml     ← add: 6 bluetape4k-graph-* aliases + neo4j-java-driver
└── graph/
    └── abuser-detection/
        ├── build.gradle.kts
        ├── README.md
        ├── README.ko.md
        ├── docs/images/readme-diagrams/
        │   ├── abuser-detection-architecture.svg
        │   └── abuser-detection-architecture.png
        └── src/
            ├── main/kotlin/io/bluetape4k/workshop/graph/abuser/
            │   ├── schema/AbuserDetectionSchema.kt
            │   ├── model/AbuseCluster.kt
            │   ├── model/AbusePath.kt
            │   ├── model/SuspiciousUserScore.kt
            │   ├── service/AbuserDetectionService.kt
            │   ├── service/AbuserDetectionSuspendService.kt
            │   └── seed/AbuserDetectionSeed.kt
            └── test/
                ├── kotlin/io/bluetape4k/workshop/graph/abuser/
                │   ├── AbstractAbuserDetectionTest.kt
                │   ├── AbstractAbuserDetectionSuspendTest.kt
                │   ├── AbuserDetectionTinkerGraphTest.kt       (no tag — default run)
                │   ├── AbuserDetectionSuspendTinkerGraphTest.kt (no tag)
                │   ├── AbuserDetectionNeo4jTest.kt             (@Tag("integration"))
                │   └── AbuserDetectionMemgraphTest.kt          (@Tag("integration"))
                └── resources/
                    ├── junit-platform.properties               (parallelism settings ONLY — do NOT add tags.exclude here)
                    └── logback-test.xml
```

---

## 8. 빌드 구성

### `gradle/libs.versions.toml` 추가

```toml
[versions]
# bluetape4k-graph version is pinned here until bluetape4k-dependencies BOM includes it.
# TODO: Once bluetape4k-dependencies governs bluetape4k-graph-bom, remove this pin and
#       reference the centrally governed alias instead. Track: bluetape4k-dependencies#<issue>.
bluetape4k-graph = "0.4.2"   # https://mvnrepository.com/artifact/io.github.bluetape4k.graph/bluetape4k-graph-bom

[libraries]
# bluetape4k-graph — versions managed by bluetape4k-graph-bom platform
bluetape4k-graph-bom       = { module = "io.github.bluetape4k.graph:bluetape4k-graph-bom", version.ref = "bluetape4k-graph" }
bluetape4k-graph-core      = { module = "io.github.bluetape4k.graph:bluetape4k-graph-core" }
bluetape4k-graph-tinkerpop = { module = "io.github.bluetape4k.graph:bluetape4k-graph-tinkerpop" }
bluetape4k-graph-neo4j     = { module = "io.github.bluetape4k.graph:bluetape4k-graph-neo4j" }
bluetape4k-graph-memgraph  = { module = "io.github.bluetape4k.graph:bluetape4k-graph-memgraph" }
neo4j-java-driver          = { module = "org.neo4j.driver:neo4j-java-driver" }
```

> 참고: `neo4j-java-driver` 버전은 `bluetape4k-graph-bom`에 의해 관리됩니다(플랫폼 가져오기는 제약 조건을 전파합니다). `version.ref`가 필요하지 않습니다.

### `settings.gradle.kts` 추가

```kotlin
includeModules("graph", false, true)   // produces project :graph-abuser-detection
```

`graalvm`과 `image-processing` 사이에 알파벳순으로 배치합니다(확인됨: `settings.gradle.kts` 순서는 `gatling → graalvm → image-processing`이고 `graph`는 `graalvm` 다음에 정렬됨).

### `graph/abuser-detection/build.gradle.kts`

```kotlin
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")   // default run: TinkerGraph only
    }
}

// Dedicated task for integration tests (requires Docker)
tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)"
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

dependencies {
    // BOM aligns all graph module versions
    implementation(platform(libs.bluetape4k.graph.bom))

    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)

    compileOnly(libs.bluetape4k.graph.neo4j)
    compileOnly(libs.bluetape4k.graph.memgraph)

    implementation(libs.bluetape4k.coroutines)
    implementation(libs.bluetape4k.logging)
    implementation(libs.kotlinx.coroutines.core.lib)

    // Test
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.kotlinx.coroutines.test.lib)
    testImplementation(libs.mockk)

    // Integration backends — declared as compileOnly above.
    // `configurations { testImplementation.extendsFrom(compileOnly, runtimeOnly) }` already
    // pulls them into the test classpath; do NOT add explicit testImplementation entries here
    // (duplicate-dependency warning + redundant resolution).
    // ❌ DO NOT add: testImplementation(libs.neo4j.java.driver)  — extendsFrom already covers it
}
```

---

## 9. 테스트 전략

### 기본 실행(TinkerGraph, Docker 없음)

```bash
./gradlew :graph-abuser-detection:test
```

연습이슈: `AbuserDetectionTinkerGraphTest` + `AbuserDetectionSuspendTinkerGraphTest`

### 통합 실행(Testcontainers을 통한 Neo4j + Memgraph)

```bash
# Run integration-tagged tests only (Docker required) via dedicated Gradle task
./gradlew :graph-abuser-detection:integrationTest

# Run ALL tests including integration (both tasks)
./gradlew :graph-abuser-detection:test :graph-abuser-detection:integrationTest
```

> ⚠️ 통합 테스트에는 **Docker가 필요합니다**. 통합 테스트 클래스는 다음을 사용해야 합니다.
> **`bluetape4k-testcontainers` 싱글톤 실행기 패턴** — NOT 인스턴스화 `GenericContainer` 직접 수행
> 또는 `DockerClientFactory.instance().isDockerAvailable`에 전화하세요. `Neo4jServer.Launcher.neo4j`을 사용하고
> `MemgraphServer.Launcher.memgraph`(또는 `bluetape4k-testcontainers`의 동등한 실행기).
> 런처 싱글톤은 Docker 가용성 확인 및 컨테이너 수명주기를 자동으로 처리합니다.
> 신뢰할 수 있는 Testcontainers 사용 패턴은 `bluetape4k-patterns` 스킬을 참조하세요.

> ⚠️ **testMutex 영향**: Neo4j/Memgraph 컨테이너가 시작되는 동안 통합 테스트는 전역 워크샵 테스트 뮤텍스를 유지합니다.
> (콜드 풀에서는 30~90초). NOT 통합 테스트를 기본 CI 매트릭스에 추가합니다. 전용 야간 작업에서만 실행하세요.

### 테스트 격리(MANDATORY)

`AbstractAbuserDetectionTest` **반드시** `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`이 있어야 합니다.
`@BeforeEach` 및 `@AfterAll`은 인스턴스 메소드로 선언될 수 있습니다(Kotlin의 경우 JUnit 5에 필요함).

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAbuserDetectionTest {

    @BeforeEach
    fun cleanGraph() {
        runCatching {
            if (ops.graphExists(graphName)) ops.dropGraph(graphName)
        }.onFailure { log.warn(it) { "dropGraph failed before test; continuing with initialize()" } }
        service.initialize()
    }
}
```

> 대칭 오류 처리: `@BeforeEach cleanGraph`은 `dropGraph`을 `runCatching`로 래핑합니다.
> 이유는 `@AfterAll` — 삭제 시 백엔드 오류가 발생하면 클래스의 모든 테스트를 다음과 같이 계단식으로 배열해서는 안 됩니다.
> 동일한 인프라 예외.

통합 백엔드 구체적인 클래스(`AbuserDetectionNeo4jTest`, `AbuserDetectionMemgraphTest`) **반드시** 다음을 포함해야 합니다.

```kotlin
@AfterAll
fun teardown() {
    // driver is owned by this test class (created in companion object / @BeforeAll).
    // Call driver.close() exactly once; do not delegate to a parent teardown that also closes it.
    runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }
        .onFailure { log.warn(it) { "dropGraph failed in @AfterAll; container may be in dirty state" } }
    driver.close()
}
```

> **드라이버 소유권**: `Driver`(Neo4j) / `BoltDriver`(Memgraph) 인스턴스가
> 구체적인 클래스의 동반 객체 또는 `@BeforeAll`. **concrete** 클래스의 `@AfterAll teardown`
> `driver.close()`를 호출하는 유일한 소유자입니다. 추상 기본 클래스는 NOT `close()`을 호출해야 합니다.
> 이렇게 하면 클래스 계층 구조 전체에서 이중 닫기가 방지됩니다.

> **고유한 그래프 이름**: 각 구체적인 통합 테스트 클래스는 다음과 같은 경우 고유한 `graphName`을 전달해야 합니다.
> `AbuserDetectionService` 구성 중:
> - `AbuserDetectionNeo4jTest`: `graphName = "abuser_neo4j"`
> - `AbuserDetectionMemgraphTest`: `graphName = "abuser_memgraph"`
> 이는 한 클래스의 `dropGraph`이 다른 클래스의 그래프 설정과 경쟁하는 것을 방지합니다.
>
> **⚠️ [P0-Impl-1] `graphName`은 NOT Bolt 데이터베이스 이름** — `Neo4jGraphOperations(driver, database)`이고
> `MemgraphGraphOperations(driver, database)` `database: String` 수락(Bolt 데이터베이스 선택기,
> 기본값 `"neo4j"` / `"memgraph"`). `"abuser_neo4j"`를 `database` 인수로 전달하면 실패합니다.
> Community Edition 컨테이너에서 `ClientException: Database does not exist`을 사용한 런타임.
> 올바른 연결: `Neo4jGraphOperations(driver)` (두 번째 인수 삭제); `graphName`은 다음으로만 흐릅니다.
> `AbuserDetectionService(ops, graphName)`을 누른 다음 `createGraph(graphName)` / `dropGraph(graphName)`에 넣습니다.

> **`AbstractAbuserDetectionSuspendTest`**: `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 요구사항은 동일하게 적용됩니다. 같은 이유로 정지 추상 기본 클래스에 주석을 선언합니다.

> **오래된 컨테이너 참고**: 컨테이너가 `withReuse(true)`을 사용하는 경우 그래프 데이터가 JVM 세션 전반에 걸쳐 지속될 수 있습니다.
> 예상치 못한 테스트 실패 시 `docker ps | grep neo4j`으로 확인하고 재시도하기 전에 오래된 컨테이너를 제거하세요.

### `AbstractAbuserDetectionTest`의 테스트 케이스

**행복 경로 테스트:**
1. `creates user and links device` — 꼭지점 + 가장자리 CRUD
2. `finds shared-device abuse cluster — returns 2 other users (seed excluded)` — 시드 사용자1 + 2명의 다른 사람이 장치를 공유합니다. `findAbuseCluster(user1)`은 `[user2, user3]`(NOT user1)을 반환합니다.
3. `explains suspicion by shared device and IP` — `explainSuspicion`은 2개의 `AbusePath` 행을 반환합니다.
4. `detects referral loops` — A→B→C→A 사이클 발견
5. `ranks user at center of identifier sharing as most suspicious` — 시드 사용자의 점수가 가장 높습니다
6. `empty graph returns empty cluster` — 경계: 이웃 없음
7. `cluster excludes unrelated users` — 부정: 클러스터에 격리된 사용자가 없음

**실패 경로 테스트(MANDATORY):**
8. `addDevice with blank deviceId throws IllegalArgumentException` — `assertFailsWith<IllegalArgumentException> { service.addDevice("", "ios") }`
9. `addUser with blank userId throws IllegalArgumentException` — `assertFailsWith<IllegalArgumentException> { service.addUser("", "KR") }`
10. `findAbuseCluster with non-existent seedUserId returns empty cluster` — 시드 ID는 그래프에 없습니다. 결과는 `users.isEmpty() && sharedIdentifiers.isEmpty()`입니다.
    구현 가드: `if (ops.findVertexById(seedUserId) == null) return AbuseCluster(seedUserId, emptyList(), emptyList())`
    (TinkerGraph은 빈 값을 반환하지만 Neo4j/Memgraph은 알 수 없는 정점 ID에 발생할 수 있습니다. 균일한 동작을 위해서는 가드가 필요합니다.)
11. `rankSuspiciousUsers returns empty list on empty graph` — 경계: 정점 없음
12. `detectReferralLoops returns empty list when no REFERRED_BY edges exist`

**추가 MANDATORY 테스트 사례:**
13. `addDevice called twice with same deviceId returns the same vertex id` — findOrCreate 멱등성: 검증문 `addDevice("device-X", "ios").id == addDevice("device-X", "ios").id`; `"device-X"`에 대해 장치 정점이 하나만 존재하는지 확인합니다.
14. `shared phone and payment method detection` — 두 사용자가 공유하는 시드 `PhoneNumber` + `PaymentMethod`; Assert `findAbuseCluster`는 두 정점 유형을 모두 포함하는 `sharedIdentifiers`를 통해 두 가지를 모두 반환합니다(`HAS_PHONE` 및 `USES_PAYMENT` 디스패치 경로 실행).
15. `cluster excludes REFERRED_BY-only reachable users` — `USES_DEVICE` AND `REFERRED_BY`을 통해 `userA → userB` 링크; `REFERRED_BY`를 통해서만 `userC → userA` 링크; `findAbuseCluster(userA).users`에 `userB`이 포함되어 있지만 NOT `userC`이 포함되어 있다고 검증문하세요.

> findOrCreate 멱등성 및 레이블 디스패치 정확성(라운드 3 회귀 수정 영역)에서 자동 실패를 방지하려면 **테스트 13-15가 필요합니다**.

### 검증 규칙

- `addUser`, `addDevice` 등은 bluetape4k 규칙에 따라 `requireNotBlank`를 호출합니다.
- 차단 예외 테스트는 `assertFailsWith<T> { }`을 사용합니다.
- `suspend fun` 예외 테스트를 일시 중지합니다: `coInvoking { suspendCall } shouldThrow T::class`.
- **Flow-함수 예외 테스트 반환**: `explainSuspicion` 및 `detectReferralLoops`은 NOT `suspend`이며 콜드 `Flow`를 반환합니다. `flow { }` 내부의 유효성 검사는 수집 시, NOT 호출 시 실행됩니다. 사용:
  ```kotlin
  runTest {
      assertFailsWith<IllegalArgumentException> {
          service.explainSuspicion(GraphElementId("")).toList()
      }
  }
  ```
  NOT `coInvoking { service.explainSuspicion(GraphElementId("")) } shouldThrow ...`을 사용하십시오. 이는 NOT 흐름을 수집하고 유효성 검사를 트리거하지 않습니다.
- Flow 취소: `Job` 수집 `rankSuspiciousUsers(...)`을 취소하고 `CancellationException`가 깔끔하게 전파되는지 확인하는 하나의 테스트를 추가합니다(`cancel()`와 함께 `runTest` 사용).
- `findAbuseCluster`은 NOT seedUserId 형식을 검증합니다. 존재하지 않는 정점은 빈 클러스터를 반환합니다.

---

## 10. 보안 참고 사항

- `PhoneNumberLabel.phone`: E.164 **해시**만 저장합니다(예: SHA-256 16진수). KDoc은 "해시된 전화번호, 일반 텍스트를 저장하지 않음"이라고 말해야 합니다.
- `PaymentMethodLabel.paymentToken`: PCI-결제 프로세서의 안전한 토큰. KDoc은 "결제 프로세서 토큰, PAN 또는 CVV을 저장하지 마세요."라고 말해야 합니다.
- `DeviceLabel.deviceId`: 해시된 장치 지문입니다. KDoc은 그렇게 말해야 합니다.
- `IpAddressLabel.ip`: 있는 그대로 저장될 수 있지만(대부분의 관할권에서는 PII이 아님) GDPR가 적용되는 경우 해시됩니다.
- 시드 데이터는 자리 표시자 값(`"device-hash-a1b2c3"`, 실제 지문 아님)을 사용합니다.

**런타임 입력 유효성 검사** (T1-H1): 서비스는 `requireNotBlank("deviceId")`(및 유사)을 호출해야 합니다.
`findOrCreate`을 호출하기 전에 모든 식별자 매개변수에 대해. 이렇게 하면 실수로 생성되는 것을 방지할 수 있습니다.
`""` 또는 그래프의 공유 식별자 감지를 손상시킬 수 있는 공백 전용 정점.
사양은 NOT 해시 형식(SHA-256 길이, base64 인코딩 등)의 유효성을 검사합니다.
발신자의 책임이며 워크숍 예시의 범위를 벗어납니다. 각 mutator에 대한 KDoc은 다음을 명시해야 합니다.
예상되는 형식(예: `@param phone E.164 SHA-256 hex hash, not plaintext`)

---

## 11. 백엔드 기능 매트릭스

| 능력 | TinkerGraph | 네오4j | 멤그래프 | AGE | FalkorDB |
|---|---|---|---|---|---|
| `createVertex` / `createEdge` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `neighbors(BOTH, multi-hop)` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `findEdgesByStartId` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `detectCycles(single label)` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `pageRank(null labels)` | ✅ (인메모리) | ✅ | ✅ | 부분적 ⚠️ | 부분적 ⚠️ |
| CI에서 테스트됨 | ✅ | 통합 | 통합 | 운동하지 않음 | 운동하지 않음 |

> ⚠️ **AGE / FalkorDB 부분 `pageRank`**: `GraphAlgorithmRepository.pageRank()`이 호출될 때
> null vertex/edge 라벨 필터를 지원하지 않는 AGE 또는 FalkorDB 백엔드, 구현
> 다음 중 하나를 수행해야 합니다.
> - `UnsupportedOperationException("pageRank with null labels not supported by this backend")`, OR 던지기
> - `WARN`을 기록하고 빈 목록을 반환합니다(자동으로 손상되지 않은 페일오픈).
>
> 이 모듈은 백엔드를 NOT연습AGE/FalkorDB합니다. 위의 동작은 정보 제공용입니다.
> 미래의 통합자. TinkerGraph, Neo4j 및 Memgraph는 모두 필수 `pageRank` 변형을 지원합니다.

---

## 12. 위험 및 완화

| 위험 | 완화 |
|------|-----------|
| testMutex은 모든 워크샵 테스트를 직렬화합니다 | 태그 통합 테스트 `@Tag("integration")`; 기본 `test` 작업에서는 이를 제외합니다. Docker 실행을 위해 `integrationTest` 작업 사용 |
| TinkerGraph ID quirk (내부적으로는 길다) | 신분증을 절대 위조하지 마십시오. 항상 `createVertex` 반환 값에서 재사용 |
| `explainSuspicion` 식별자 유형별 N×M 호출 | `IdentifierEdgeLabel.all.size = 4`에 의해 제한됨; 예시 범위에 허용됨 |
| PageRank은 모든 정점 유형을 반복합니다 | `User`에 대한 서비스 계층 사후 필터; 팬인이 낮기 때문에 식별자 정점이 지배적이지 않습니다 |
| Blueprint neo4j-java-driver 버전에는 핀이 필요합니다 | `bluetape4k-graph-bom` 플랫폼에서 관리됩니다. 명시적인 버전이 필요하지 않습니다 |
| libs.versions.toml에 `bluetape4k-graph` 버전 고정 | `bluetape4k-dependencies` BOM이(가) 관리할 때까지 일시적입니다. TODO 댓글 추가됨 |
| 중복된 식별자 정점(findOrCreate 실패) | §5.1 findOrCreate 조회 사양 참조 — 생성 전 키 속성으로 정점 조회 |
| AGE/FalkorDB 부분 pageRank | `UnsupportedOperationException`을 던지거나 WARN 로그와 함께 빈 목록을 반환합니다. §11 참조 |

---

## 13. DoD (완료의 정의)

- [ ] `graph/abuser-detection/` §7에 따라 모든 파일로 생성된 모듈
- [ ] `settings.gradle.kts` 및 `gradle/libs.versions.toml` 업데이트됨
- [ ] `AbuserDetectionSchema.kt` 꼭지점 라벨 5개와 모서리 라벨 5개 모두 포함
- [ ] `AbuserDetectionService` + `AbuserDetectionSuspendService` 모든 메소드 구현
- [ ] `AbstractAbuserDetectionTest`은 15개의 테스트 사례를 모두 포함합니다(7개의 행복한 경로 + 5개의 실패 경로 + 3개의 추가: 멱등성, phone/payment 디스패치, REFERRED_BY 제외)
- [ ] `AbstractAbuserDetectionSuspendTest`은 `runTest` + 1 Flow 취소 테스트 = 총 16개의 동일한 15개 테스트 케이스를 다룹니다.
- [ ] TinkerGraph Docker 없이 테스트를 통과했습니다. `./gradlew :graph-abuser-detection:test`
- [ ] Neo4j 및 Memgraph 통합 테스트는 기본적으로 태그가 지정되고 건너뛰었습니다.
- [ ] `README.md` + `README.ko.md` 아키텍처 다이어그램으로 작성
- [ ] `docs/images/readme-diagrams/abuser-detection-architecture.{svg,png}`의 다이어그램 자산
- [ ] 모든 공개 API에 대한 영어 KDoc
- [ ] `docs/lessons/2026-05-25-graph-abuser-detection.md`이(가) PR 이전에 커밋되었습니다.
- [ ] CI 게이트: 모든 확인 SUCCESS

---

## 부록 — 반복 로그 검토

| 라운드 | 리뷰어 | P0 | P1 | P2 | P3 | 적용된 커밋 |
|-------|----------|----|----|----|----|----------------|
| 1라운드 | 개발자(소네트) | 0 | 5 | 3 | 1 | 사양 v2 |
| 1라운드 | 보안(소네트) | 0 | 2 | 2 | 0 | 사양 v2(S1/S2 다운그레이드된 워크샵 범위) |
| 1라운드 | Ops/SRE (소네트) | 0 | 4 | 2 | 0 | 사양 v2 |
| 1라운드 | User/Caller(하이쿠) | 0 | 3 | 4 | 0 | 사양 v2(U2/U3 다운그레이드된 impl 단계) |
| 1라운드 | 비평가(오푸스) | — | — | — | — | C1-C6 HIGH 고정; C7–C15 구현 단계 |
| 1라운드 | 코덱스 CLI | 0 | 2 | 6 | 2 | HIGH-Codex1(순회 수정) + HIGH-Codex2(findOrCreate) 사양 v2에서 수정됨 |
| **1라운드 결승전** | **모든 리뷰어** | **0** | **0** | 암시적 단계 | 낮음 | 사양 v2 커밋됨 |
| 2라운드 | 6계층 자문위원(Sonnet) | 0 | 9 | 2 | 0 | 사양 v3(T1-H1 해시 유효성 검사 메모, T2-H1/H2 드라이버+graphName 격리, T3-H1 버전 관리 TODO, T4-H1 타임스탬프 지침, T4-H2 Flow 취소 계약, T5-H1 실패 경로 테스트, T5-H2 백엔드 대체, T6-H1 라벨 디스패치 최적화) |
| 2라운드 | 개발자(소네트) | 0 | 2 | 2 | 0 | 사양 v3에서 (H1 maxDepth 제거됨, H2 ALL_IDENTIFIER_EDGE_LABELS → IdentifierEdgeLabel.all, M1 .value unwrap note, M2 findOrCreate 조회) |
| 2라운드 | Ops/SRE (소네트) | 0 | 2 | 1 | 0 | 사양 v3에서 (H1 @TestInstance(PER_CLASS), H2 integrationTest Gradle 작업, M 비대칭 runCatching) |
| 3라운드 | 개발자(소네트) | 0 | 1→0 | 2 | 0 | 사양 v4: NEW H1 레이블 디스패치 버그(정점 레이블과 가장자리 레이블 문자열 불일치 → 항상 false)가 명시적인 맵 조회로 수정됨 |
| 3라운드 | Ops/SRE (소네트) | 0 | 0 | 2 | 0 | 사양 v4: M1 @AfterAll .onFailure 로그가 추가되었습니다. M2 AbstractAbuserDetectionSuspendTest @TestInstance 멘션 추가됨 |
| **3라운드 결승전** | **모든 리뷰어** | **0** | **0** | 폴란드어 | 낮음 | **CONVERGED ✅** |
| **3-R단계 계획 검토** | 3r-전달, 3r-테스터, 3r-구현자, 3r-건축가 | **0** | **19** | 9 | 3 | v5: §6 mapIndexed→withIndex 수정; §13 DoD 7→12 테스트; §8 빌드 스크립트 중복 dep 제거 + 위치 수정; 테스트 케이스 13-15가 추가되었습니다. Flow 예외 테스트 패턴이 명확해졌습니다. §9 integrationTest 참고; spec v5 적용 |
| **3-R단계 2라운드 전 수정** | 인라인 어드바이저 | 0 | 3 | 0 | 0 | spec v6 (§13 DoD 12→15/16) + plan v3 (T5-1 Flow.forEach→직접 파이프라인; T10-1~4 graphName 초기화 순서; API 클래스 이름 확인됨) |
| **3단계-R 2라운드** | 4관점(implementer/tester/architect/delivery) + 6계층 어드바이저 | 0 | 7 | 5 | 2 | — |
| **3단계-R 2차 적용** | 인라인 심사 | 0 | 7→0 | 1 MEDIUM | 0 | spec v7 + plan v4: §8 모순되는 testImpl 줄이 수정되었습니다. §5.1 findOrCreate "첫 번째 생성 승리" 계약; §5.1 linkDevice 매개변수 이름 바꾸기(userVertexId/deviceVertexId); T2-1 보안 KDoc; T8-1 Flow 패턴 취소(async+deferred/backgroundScope); T10-2/T10-4 @Tag("통합"); T10-3/T10-4 @AfterAll 드라이버.닫기(); T7-1 test7 하위 검증문 |
| **3단계-R 3라운드** | 구현자(Opus) P0=2,P1=3; 테스터(Opus) P0=0,P1=4; 건축가(소네트) P0=0,P1=3; 배송(소네트) P0=0,P1=3 | 원시 P0=2, P1=13 | 엄격한 분류 P0=2, P1=7 | 8 | 0 | — |
| **3단계-R 3차 적용** | 인라인 분류(엄격: 컴파일 오류/런타임 충돌/자동 테스트 우회) | P0=2→0, P1=7→0 | 0 | 8 | 0 | 사양 v8 + 계획 v5: [P0-1] 작업 생성자 데이터베이스≠graphName (§9 + T10-1~4); [P0-2] 일시 중지 findAbuseCluster Flow.toList (T5-1); [P1-1] explainSuspicion Flow 구성 (T5-1); [P1-3] T10-3/4 일시 중지 작업 생성자 수정; [P1-Arch-3] 구성{}.get() T1-3; [P1-Deliv-1] seedIsolatedUser는 클러스터+격리(T6-1+T7-1)를 생성합니다. [P1-테스트-1] @BeforeEach cleanGraph() runTest+try/catch 패턴 (T8-1); [P1-테스트-2] test16 시드+지연 (T8-1); [P1-Test-4] 3/4/5/14 구체적인 검증문(T7-1) 테스트 |

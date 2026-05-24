# Design Spec — graph/abuser-detection Workshop Module

**Date**: 2026-05-25  
**Issue**: [#12](https://github.com/bluetape4k/bluetape4k-workshop/issues/12)  
**Status**: Draft  
**Author**: AI-assisted (Claude)

---

## 1. Problem Statement

Issue #12 requires a primary `bluetape4k-graph` workshop example demonstrating **abuser detection** — the process of finding suspicious users who operate multiple accounts by sharing identifiers such as devices, IP addresses, phone numbers, and payment methods.

The existing `bluetape4k-graph/examples/fraud-detection-examples/` models financial fraud (money-flow circular transfers between homogeneous Account vertices). The abuser-detection scenario is architecturally different: it uses a **heterogeneous graph** where `User` vertices connect to multiple identifier vertex types via shared-identifier edges. Abusers are revealed by the **transitive shared-identifier neighborhood** (users who share any identifier reachable within a few hops).

---

## 2. Goals

- Demonstrate why graph modeling is superior to SQL joins for multi-account abuse detection.
- Show bluetape4k-graph APIs: vertex/edge CRUD, BFS-based cluster traversal, cycle detection, PageRank.
- Provide an in-memory (TinkerGraph) fast test path and opt-in integration tests for Neo4j and Memgraph.
- Showcase blocking + suspend service patterns with the same domain.

---

## 3. Non-Goals

- REST API endpoint (no Spring Boot in this module).
- AGE / FalkorDB integration tests (documented as supported but not exercised in CI).
- Real PII data (all identifiers are hashed/tokenized placeholders).

---

## 4. Graph Domain Model

### 4.1 Vertex Labels

| Object | Label | Key Properties |
|--------|-------|----------------|
| `UserLabel` | `"User"` | `userId`, `createdAt`, `signupCountry` |
| `DeviceLabel` | `"Device"` | `deviceId`, `platform` |
| `IpAddressLabel` | `"IpAddress"` | `ip`, `asn` |
| `PhoneNumberLabel` | `"PhoneNumber"` | `phone` (E.164 hash) |
| `PaymentMethodLabel` | `"PaymentMethod"` | `paymentToken` (PCI-safe token), `brand` |

### 4.2 Edge Labels

| Object | Label | From → To | Key Properties |
|--------|-------|-----------|----------------|
| `UsesDeviceLabel` | `"USES_DEVICE"` | User → Device | `firstSeenAt` |
| `UsesIpLabel` | `"USES_IP"` | User → IpAddress | `firstSeenAt` |
| `HasPhoneLabel` | `"HAS_PHONE"` | User → PhoneNumber | `verifiedAt` |
| `UsesPaymentLabel` | `"USES_PAYMENT"` | User → PaymentMethod | `firstChargedAt` |
| `ReferredByLabel` | `"REFERRED_BY"` | User → User | `occurredAt` |

### 4.3 Abuse Detection Logic

- **Abuse Cluster**: Set of users reachable from a seed user via a **direct shared-identifier hop** (User → Identifier → User, exactly 1 identifier level). `REFERRED_BY` edges are excluded from cluster traversal.
- **Suspicion Explanation**: For each identifier edge label, find identifiers connected to a user and return other users sharing those identifiers.
- **Referral Loops**: Cycle detection on `REFERRED_BY` edges (reward farming detection).
- **Suspicious Ranking**: PageRank over full graph; post-filter to User vertices (identifier-bridge topology amplifies scores of users in dense sharing clusters).

> **Note**: `findAbuseCluster` intentionally implements a fixed 1-hop identifier traversal (User→Identifier→User = 2 edge hops). This is sufficient for direct-sharing detection and avoids unbounded traversal in a workshop setting.

---

## 5. API Design

### 5.1 AbuserDetectionService (blocking)

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
    // findOrCreate lookup: ops.findVerticesByLabel(DeviceLabel.label).firstOrNull { it.properties["deviceId"] == deviceId }
    // before creating; if found, return existing vertex.
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

    fun linkDevice(userId: GraphElementId, deviceId: GraphElementId, firstSeenAt: String = "")
    fun linkIp(userId: GraphElementId, ipId: GraphElementId, firstSeenAt: String = "")
    fun linkPhone(userId: GraphElementId, phoneId: GraphElementId, verifiedAt: String = "")
    fun linkPayment(userId: GraphElementId, paymentId: GraphElementId, firstChargedAt: String = "")
    fun linkReferral(referrerId: GraphElementId, referredId: GraphElementId, occurredAt: String = "")

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

> **Per-backend graph name isolation**: Each concrete integration test class must supply a unique
> `graphName` to `AbuserDetectionService` (e.g., `"abuser_neo4j"`, `"abuser_memgraph"`) to
> prevent `dropGraph` in one class from racing with graph setup in another class running
> concurrently in the same JVM.

### 5.2 AbuserDetectionSuspendService (coroutine)

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

### 5.3 Result Types

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

## 6. Algorithm Implementation Notes

### `findAbuseCluster(seedUserId)`

> **Important**: Do NOT use `edgeLabel = null` — this would traverse `REFERRED_BY` edges
> and pull unrelated users into the cluster. Use identifier edge labels only.
>
> **`IdentifierEdgeLabel` vs `String`**: The `ops` API (`NeighborOptions`, `findEdgesByStartId`)
> expects a raw `String` for `edgeLabel`. Pass `edgeLabel.value` (not the `IdentifierEdgeLabel`
> object directly) at every call site.

Fixed 1-hop identifier traversal (`User → Identifier → User`):

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

> **Label-dispatch optimization**: Instead of querying all 4 edge labels per identifier vertex
> (O(4N) round trips), the algorithm dispatches exactly 1 query per identifier vertex by
> matching the vertex's own label to its corresponding edge label. This reduces to O(N) queries.

Returns `AbuseCluster(users = emptyList(), sharedIdentifiers = emptyList())` when seed has no identifier links or seedUserId does not exist — no exception.

### `explainSuspicion(userId)`
For each `edgeLabel` in `IdentifierEdgeLabel.all` (`USES_DEVICE`, `USES_IP`, `HAS_PHONE`, `USES_PAYMENT`):
1. `ops.findEdgesByStartId(userId, edgeLabel.value)` → edges to identifier vertices
2. For each identifier edge: `ops.neighbors(identifierId, NeighborOptions(edgeLabel.value, INCOMING, maxDepth=1))`
3. Filter out `userId` itself; emit `AbusePath(userId, otherUserId, identifierVertex, edgeLabel)` per other user found

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

> **`Flow.mapIndexed` does not exist** in `kotlinx.coroutines.flow`. In `AbuserDetectionSuspendService`,
> the suspend `rankSuspiciousUsers` returns `Flow<SuspiciousUserScore>`. Use `.withIndex().map { (idx, s) -> ... }`
> (Flow analogue of `mapIndexed`) or accumulate with a `var rank = 1` counter.
> The blocking `AbuserDetectionService.rankSuspiciousUsers` returns `List<SuspiciousUserScore>` and
> may use `mapIndexed` directly.

---

## 7. Module Structure

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

## 8. Build Configuration

### `gradle/libs.versions.toml` additions

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

> Note: `neo4j-java-driver` version is managed by `bluetape4k-graph-bom` (platform import propagates constraints). No `version.ref` needed.

### `settings.gradle.kts` addition

```kotlin
includeModules("graph", false, true)   // produces project :graph-abuser-detection
```

Place alphabetically between `graalvm` and `image-processing` (verified: `settings.gradle.kts` order is `gatling → graalvm → image-processing`; `graph` sorts after `graalvm`).

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
    testImplementation(libs.neo4j.java.driver)
}
```

---

## 9. Test Strategy

### Default run (TinkerGraph, no Docker)

```bash
./gradlew :graph-abuser-detection:test
```

Exercises: `AbuserDetectionTinkerGraphTest` + `AbuserDetectionSuspendTinkerGraphTest`

### Integration run (Neo4j + Memgraph via Testcontainers)

```bash
# Run integration-tagged tests only (Docker required) via dedicated Gradle task
./gradlew :graph-abuser-detection:integrationTest

# Run ALL tests including integration (both tasks)
./gradlew :graph-abuser-detection:test :graph-abuser-detection:integrationTest
```

> ⚠️ **Docker required** for integration tests. Integration test classes must use
> **`bluetape4k-testcontainers` singleton launcher patterns** — do NOT instantiate `GenericContainer` directly
> or call `DockerClientFactory.instance().isDockerAvailable`. Use `Neo4jServer.Launcher.neo4j` and
> `MemgraphServer.Launcher.memgraph` (or equivalent launchers from `bluetape4k-testcontainers`).
> The launcher singleton handles Docker availability checks and container lifecycle automatically.
> See `bluetape4k-patterns` skill for the authoritative Testcontainers usage pattern.

> ⚠️ **testMutex impact**: Integration tests hold the global workshop test mutex while Neo4j/Memgraph containers start
> (30–90s on cold pull). Do NOT add integration tests to the default CI matrix. Run them only in a dedicated nightly job.

### Test isolation (MANDATORY)

`AbstractAbuserDetectionTest` **must** have `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` so that
`@BeforeEach` and `@AfterAll` can be declared as instance methods (required by JUnit 5 for Kotlin):

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

> Symmetric error handling: `@BeforeEach cleanGraph` wraps `dropGraph` in `runCatching` for the same
> reason as `@AfterAll` — a backend error on drop must not cascade all tests in the class with the
> same infrastructure exception.

Integration-backend concrete classes (`AbuserDetectionNeo4jTest`, `AbuserDetectionMemgraphTest`) **must** include:

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

> **Driver ownership**: The `Driver` (Neo4j) / `BoltDriver` (Memgraph) instance is created in the
> concrete class's companion object or `@BeforeAll`. The `@AfterAll teardown` in the **concrete** class
> is the sole owner that calls `driver.close()`. The abstract base class must NOT call `close()`.
> This prevents double-close across the class hierarchy.

> **Unique graph names**: Each concrete integration test class must pass a unique `graphName` when
> constructing `AbuserDetectionService`:
> - `AbuserDetectionNeo4jTest`: `graphName = "abuser_neo4j"`
> - `AbuserDetectionMemgraphTest`: `graphName = "abuser_memgraph"`
> This prevents `dropGraph` in one class from racing with graph setup in another class.

> **`AbstractAbuserDetectionSuspendTest`**: The `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` requirement applies equally. Declare the annotation on the suspend abstract base class for the same reason.

> **Stale container note**: If containers use `withReuse(true)`, graph data may persist across JVM sessions.
> On unexpected test failures, verify with `docker ps | grep neo4j` and remove stale containers before retrying.

### Test cases in `AbstractAbuserDetectionTest`

**Happy-path tests:**
1. `creates user and links device` — vertex + edge CRUD
2. `finds shared-device abuse cluster — returns 2 other users (seed excluded)` — seed user1 + 2 others share device; `findAbuseCluster(user1)` returns `[user2, user3]` (NOT user1)
3. `explains suspicion by shared device and IP` — `explainSuspicion` returns 2 `AbusePath` rows
4. `detects referral loops` — A→B→C→A cycle found
5. `ranks user at center of identifier sharing as most suspicious` — seed user has highest score
6. `empty graph returns empty cluster` — boundary: zero neighbors
7. `cluster excludes unrelated users` — negative: isolated user absent from cluster

**Failure-path tests (MANDATORY):**
8. `addDevice with blank deviceId throws IllegalArgumentException` — `assertFailsWith<IllegalArgumentException> { service.addDevice("", "ios") }`
9. `addUser with blank userId throws IllegalArgumentException` — `assertFailsWith<IllegalArgumentException> { service.addUser("", "KR") }`
10. `findAbuseCluster with non-existent seedUserId returns empty cluster` — seed ID not in graph; result has `users.isEmpty() && sharedIdentifiers.isEmpty()`
    Implementation guard: `if (ops.findVertexById(seedUserId) == null) return AbuseCluster(seedUserId, emptyList(), emptyList())`
    (TinkerGraph returns empty, but Neo4j/Memgraph may throw on unknown vertex ID — guard required for uniform behavior.)
11. `rankSuspiciousUsers returns empty list on empty graph` — boundary: no vertices
12. `detectReferralLoops returns empty list when no REFERRED_BY edges exist`

**Additional MANDATORY test cases:**
13. `addDevice called twice with same deviceId returns the same vertex id` — findOrCreate idempotency: assert `addDevice("device-X", "ios").id == addDevice("device-X", "ios").id`; verify only one Device vertex exists for `"device-X"`.
14. `shared phone and payment method detection` — seed two users sharing `PhoneNumber` + `PaymentMethod`; assert `findAbuseCluster` returns both via `sharedIdentifiers` containing both vertex types (exercises `HAS_PHONE` and `USES_PAYMENT` dispatch paths).
15. `cluster excludes REFERRED_BY-only reachable users` — link `userA → userB` via `USES_DEVICE` AND `REFERRED_BY`; link `userC → userA` via `REFERRED_BY` only; assert `findAbuseCluster(userA).users` contains `userB` but NOT `userC`.

> **Test 13–15 are required** to prevent silent failures in findOrCreate idempotency and label-dispatch correctness (the Round 3 regression fix area).

### Validation rules

- `addUser`, `addDevice`, etc. call `requireNotBlank` per bluetape4k conventions.
- Blocking exception tests use `assertFailsWith<T> { }`.
- Suspend `suspend fun` exception tests: `coInvoking { suspendCall } shouldThrow T::class`.
- **Flow-returning function exception tests**: `explainSuspicion` and `detectReferralLoops` are NOT `suspend` — they return a cold `Flow`. Validation inside `flow { }` runs at collection time, NOT at call time. Use:
  ```kotlin
  runTest {
      assertFailsWith<IllegalArgumentException> {
          service.explainSuspicion(GraphElementId("")).toList()
      }
  }
  ```
  Do NOT use `coInvoking { service.explainSuspicion(GraphElementId("")) } shouldThrow ...` — this does NOT collect the flow and will not trigger the validation.
- Flow cancellation: add one test that cancels a `Job` collecting `rankSuspiciousUsers(...)` and asserts `CancellationException` propagates cleanly (use `runTest` with `cancel()`).
- `findAbuseCluster` does NOT validate seedUserId format — a non-existent vertex returns an empty cluster.

---

## 10. Security Notes

- `PhoneNumberLabel.phone`: store E.164 **hash** only (e.g. SHA-256 hex). KDoc must say "hashed phone number; never store plaintext".
- `PaymentMethodLabel.paymentToken`: PCI-safe token from payment processor. KDoc must say "payment processor token; never store PAN or CVV".
- `DeviceLabel.deviceId`: hashed device fingerprint. KDoc must say so.
- `IpAddressLabel.ip`: may be stored as-is (non-PII in most jurisdictions) but hash if GDPR applies.
- Seed data uses placeholder values (`"device-hash-a1b2c3"`, not real fingerprints).

**Runtime input validation** (T1-H1): The service must call `requireNotBlank("deviceId")` (and similar)
for all identifier parameters before calling `findOrCreate`. This prevents accidentally creating
`""` or whitespace-only vertices that would corrupt the graph's shared-identifier detection.
The spec does NOT validate hash format (SHA-256 length, base64 encoding, etc.) — that is the
caller's responsibility and out of scope for a workshop example. KDoc on each mutator must state
the expected format (e.g., `@param phone E.164 SHA-256 hex hash, not plaintext`).

---

## 11. Backend Capability Matrix

| Capability | TinkerGraph | Neo4j | Memgraph | AGE | FalkorDB |
|---|---|---|---|---|---|
| `createVertex` / `createEdge` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `neighbors(BOTH, multi-hop)` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `findEdgesByStartId` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `detectCycles(single label)` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `pageRank(null labels)` | ✅ (in-memory) | ✅ | ✅ | partial ⚠️ | partial ⚠️ |
| Tested in CI | ✅ | integration | integration | not exercised | not exercised |

> ⚠️ **AGE / FalkorDB partial `pageRank`**: When `GraphAlgorithmRepository.pageRank()` is invoked on
> an AGE or FalkorDB backend that does not support null vertex/edge label filters, the implementation
> must either:
> - Throw `UnsupportedOperationException("pageRank with null labels not supported by this backend")`, OR
> - Log a `WARN` and return an empty list (fail-open, not silently corrupt).
>
> This module does NOT exercise AGE/FalkorDB backends. The behavior above is informational for
> future integrators. TinkerGraph, Neo4j, and Memgraph all support the required `pageRank` variant.

---

## 12. Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| testMutex serializes all workshop tests | Tag integration tests `@Tag("integration")`; default `test` task excludes them; use `integrationTest` task for Docker run |
| TinkerGraph ID quirk (Long internally) | Never fabricate IDs; always reuse from `createVertex` return value |
| `explainSuspicion` N×M calls per identifier type | Bounded by `IdentifierEdgeLabel.all.size = 4`; acceptable for example scope |
| PageRank iterates over all vertex types | Service-layer post-filter to `User`; identifier vertices don't dominate because they have low fan-in |
| Blueprint neo4j-java-driver version needs pin | Managed by `bluetape4k-graph-bom` platform; no explicit version needed |
| `bluetape4k-graph` version pin in libs.versions.toml | Temporary until `bluetape4k-dependencies` BOM governs it; TODO comment added |
| Duplicate identifier vertices (findOrCreate failure) | See §5.1 findOrCreate lookup spec — vertex lookup by key property before create |
| AGE/FalkorDB partial pageRank | Throw `UnsupportedOperationException` or return empty list with WARN log; see §11 |

---

## 13. DoD (Definition of Done)

- [ ] `graph/abuser-detection/` module created with all files per §7
- [ ] `settings.gradle.kts` and `gradle/libs.versions.toml` updated
- [ ] `AbuserDetectionSchema.kt` with all 5 vertex labels and 5 edge labels
- [ ] `AbuserDetectionService` + `AbuserDetectionSuspendService` implement all methods
- [ ] `AbstractAbuserDetectionTest` covers all 12 test cases (7 happy-path + 5 failure-path)
- [ ] `AbstractAbuserDetectionSuspendTest` covers same 12 test cases with `runTest`
- [ ] TinkerGraph tests pass without Docker: `./gradlew :graph-abuser-detection:test`
- [ ] Neo4j and Memgraph integration tests tagged and skipped by default
- [ ] `README.md` + `README.ko.md` written with architecture diagram
- [ ] Diagram assets at `docs/images/readme-diagrams/abuser-detection-architecture.{svg,png}`
- [ ] English KDoc on all public API
- [ ] `docs/lessons/2026-05-25-graph-abuser-detection.md` committed before PR
- [ ] CI gate: all checks SUCCESS

---

## Appendix — Review Iteration Log

| Round | Reviewer | P0 | P1 | P2 | P3 | Applied commit |
|-------|----------|----|----|----|----|----------------|
| Round 1 | Developer (Sonnet) | 0 | 5 | 3 | 1 | in spec v2 |
| Round 1 | Security (Sonnet) | 0 | 2 | 2 | 0 | in spec v2 (S1/S2 downgraded workshop scope) |
| Round 1 | Ops/SRE (Sonnet) | 0 | 4 | 2 | 0 | in spec v2 |
| Round 1 | User/Caller (Haiku) | 0 | 3 | 4 | 0 | in spec v2 (U2/U3 downgraded impl phase) |
| Round 1 | Critic (Opus) | — | — | — | — | C1–C6 HIGH fixed; C7–C15 impl phase |
| Round 1 | Codex CLI | 0 | 2 | 6 | 2 | HIGH-Codex1 (traversal fix) + HIGH-Codex2 (findOrCreate) fixed in spec v2 |
| **Round 1 final** | **All reviewers** | **0** | **0** | impl-phase | low | spec v2 committed |
| Round 2 | 6-tier advisor (Sonnet) | 0 | 9 | 2 | 0 | in spec v3 (T1-H1 hash validation note, T2-H1/H2 driver+graphName isolation, T3-H1 version governance TODO, T4-H1 timestamp guidance, T4-H2 Flow cancellation contract, T5-H1 failure-path tests, T5-H2 backend fallback, T6-H1 label-dispatch optimization) |
| Round 2 | Developer (Sonnet) | 0 | 2 | 2 | 0 | in spec v3 (H1 maxDepth removed, H2 ALL_IDENTIFIER_EDGE_LABELS → IdentifierEdgeLabel.all, M1 .value unwrap note, M2 findOrCreate lookup) |
| Round 2 | Ops/SRE (Sonnet) | 0 | 2 | 1 | 0 | in spec v3 (H1 @TestInstance(PER_CLASS), H2 integrationTest Gradle task, M asymmetric runCatching) |
| Round 3 | Developer (Sonnet) | 0 | 1→0 | 2 | 0 | in spec v4: NEW H1 label-dispatch bug (vertex label vs edge label string mismatch → always false) fixed with explicit Map lookup |
| Round 3 | Ops/SRE (Sonnet) | 0 | 0 | 2 | 0 | in spec v4: M1 @AfterAll .onFailure log added; M2 AbstractAbuserDetectionSuspendTest @TestInstance mention added |
| **Round 3 final** | **All reviewers** | **0** | **0** | polish | low | **CONVERGED ✅** |
| **Step 3-R Plan Review** | 3r-delivery, 3r-tester, 3r-implementer, 3r-architect | **0** | **19** | 9 | 3 | v5: §6 mapIndexed→withIndex fix; §13 DoD 7→12 tests; §8 build script duplicate dep removed + positions fixed; test cases 13–15 added; Flow exception test pattern clarified; §9 integrationTest note; spec v5 applied |

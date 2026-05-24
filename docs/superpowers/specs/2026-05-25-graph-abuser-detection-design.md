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

- **Abuse Cluster**: Set of users reachable from a seed user via `BOTH`-direction BFS of depth ≤ 4, crossing shared-identifier vertices as intermediate hops.
- **Suspicion Explanation**: For each identifier edge label, find identifiers connected to a user and return other users sharing those identifiers.
- **Referral Loops**: Cycle detection on `REFERRED_BY` edges (reward farming detection).
- **Suspicious Ranking**: PageRank over full graph; post-filter to User vertices (identifier-bridge topology amplifies scores of users in dense sharing clusters).

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
    fun findAbuseCluster(seedUserId: GraphElementId, maxDepth: Int = 4): AbuseCluster
    fun explainSuspicion(userId: GraphElementId): List<AbusePath>
    fun detectReferralLoops(maxDepth: Int = 6, maxCycles: Int = 20): List<GraphCycle>
    fun rankSuspiciousUsers(limit: Int = 10): List<SuspiciousUserScore>
}
```

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

    // Streaming queries
    suspend fun findAbuseCluster(seedUserId: GraphElementId, maxDepth: Int = 4): AbuseCluster
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

### `findAbuseCluster(seedUserId, maxDepth)`

> **Important**: Do NOT use `edgeLabel = null` — this would traverse `REFERRED_BY` edges
> and pull unrelated users into the cluster. Use identifier edge labels only.

Multi-hop BFS restricted to identifier edges (enforces `User → Identifier → User` pattern):

```kotlin
val visited = mutableSetOf<GraphElementId>()
val identifierVertices = mutableListOf<GraphVertex>()
val clusterUsers = mutableListOf<GraphVertex>()

// Step 1: hop out from seed user to identifier vertices
val seedIdentifiers = ALL_IDENTIFIER_EDGE_LABELS.flatMap { edgeLabel ->
    ops.neighbors(seedUserId, NeighborOptions(edgeLabel = edgeLabel, direction = OUTGOING, maxDepth = 1))
}
identifierVertices += seedIdentifiers

// Step 2: for each identifier, find all connected users
seedIdentifiers.forEach { identifierVertex ->
    ALL_IDENTIFIER_EDGE_LABELS.forEach { edgeLabel ->
        val connectedUsers = ops.neighbors(
            identifierVertex.id,
            NeighborOptions(edgeLabel = edgeLabel, direction = INCOMING, maxDepth = 1)
        ).filter { it.id != seedUserId && it.id !in visited && it.label == UserLabel.label }
        clusterUsers += connectedUsers
        visited += connectedUsers.map { it.id }
    }
}

→ AbuseCluster(seedUserId, users = clusterUsers.distinct(), sharedIdentifiers = identifierVertices.distinct())
```

Returns `AbuseCluster(users = emptyList(), sharedIdentifiers = emptyList())` when seed has no identifier links — no exception.

### `explainSuspicion(userId)`
For each `edgeLabel` in `ALL_IDENTIFIER_EDGE_LABELS` (`USES_DEVICE`, `USES_IP`, `HAS_PHONE`, `USES_PAYMENT`):
1. `ops.findEdgesByStartId(userId, edgeLabel)` → edges to identifier vertices
2. For each identifier edge: `ops.neighbors(identifierId, NeighborOptions(edgeLabel, INCOMING, maxDepth=1))`
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
  → mapIndexed { idx, s -> SuspiciousUserScore(s.vertex, s.score, idx + 1) }
```

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
                    ├── junit-platform.properties               (excludes "integration" tag by default)
                    └── logback-test.xml
```

---

## 8. Build Configuration

### `gradle/libs.versions.toml` additions

```toml
[versions]
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

Place alphabetically between `gatling` and `image-processing`.

### `graph/abuser-detection/build.gradle.kts`

```kotlin
configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform { }
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

    // Integration backends (testImplementation so drivers resolve at test classpath)
    testImplementation(libs.bluetape4k.graph.neo4j)
    testImplementation(libs.bluetape4k.graph.memgraph)
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
# Run integration-tagged tests only (Docker required)
./gradlew :graph-abuser-detection:test -Djunit.jupiter.tags="integration"

# Run ALL tests including integration (unfiltered)
./gradlew :graph-abuser-detection:test -Djunit.jupiter.execution.exclude.tags=""
```

> ⚠️ **Docker required** for integration tests. Integration test classes must use
> `org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable` or `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)`
> in `@BeforeAll` to produce a readable skip instead of an initialization error on machines without Docker.

> ⚠️ **testMutex impact**: Integration tests hold the global workshop test mutex while Neo4j/Memgraph containers start
> (30–90s on cold pull). Do NOT add integration tests to the default CI matrix. Run them only in a dedicated nightly job.

### Test isolation (MANDATORY)

`AbstractAbuserDetectionTest` **must** include:

```kotlin
@BeforeEach
fun cleanGraph() {
    if (ops.graphExists(graphName)) ops.dropGraph(graphName)
    service.initialize()
}
```

Integration-backend concrete classes (`AbuserDetectionNeo4jTest`, `AbuserDetectionMemgraphTest`) **must** include:

```kotlin
@AfterAll
fun teardown() {
    runCatching { if (ops.graphExists(graphName)) ops.dropGraph(graphName) }
    driver.close()
}
```

> Stale container note: If containers use `withReuse(true)`, graph data may persist across JVM sessions.
> On unexpected test failures, verify with `docker ps | grep neo4j` and remove stale containers before retrying.

### Test cases in `AbstractAbuserDetectionTest`

1. `creates user and links device` — vertex + edge CRUD
2. `finds shared-device abuse cluster — returns 2 other users (seed excluded)` — seed user1 + 2 others share device; `findAbuseCluster(user1)` returns `[user2, user3]` (NOT user1)
3. `explains suspicion by shared device and IP` — `explainSuspicion` returns 2 `AbusePath` rows
4. `detects referral loops` — A→B→C→A cycle found
5. `ranks user at center of identifier sharing as most suspicious` — seed user has highest score
6. `empty graph returns empty cluster` — boundary: zero neighbors
7. `cluster excludes unrelated users` — negative: isolated user absent from cluster

### Validation rules

- `addUser`, `addDevice`, etc. call `requireNotBlank` per bluetape4k conventions.
- `findAbuseCluster` validates `maxDepth in 1..10` with `require`.
- Suspend tests use `runTest { ... }` and `coInvoking { } shouldThrow T::class` for expected exceptions.
- Blocking exception tests use `assertFailsWith<T> { }`.

---

## 10. Security Notes

- `PhoneNumberLabel.phone`: store E.164 **hash** only (e.g. SHA-256 hex). KDoc must say "hashed phone number; never store plaintext".
- `PaymentMethodLabel.paymentToken`: PCI-safe token from payment processor. KDoc must say "payment processor token; never store PAN or CVV".
- `DeviceLabel.deviceId`: hashed device fingerprint. KDoc must say so.
- Seed data uses placeholder values (`"device-hash-a1b2c3"`, not real fingerprints).

---

## 11. Backend Capability Matrix

| Capability | TinkerGraph | Neo4j | Memgraph | AGE | FalkorDB |
|---|---|---|---|---|---|
| `createVertex` / `createEdge` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `neighbors(BOTH, multi-hop)` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `findEdgesByStartId` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `detectCycles(single label)` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `pageRank(null labels)` | ✅ (in-memory) | ✅ | ✅ | partial | partial |
| Tested in CI | ✅ | integration | integration | not exercised | not exercised |

---

## 12. Risks and Mitigations

| Risk | Mitigation |
|------|-----------|
| testMutex serializes all workshop tests | Tag integration tests `@Tag("integration")`; default run is TinkerGraph-only (< 5s) |
| TinkerGraph ID quirk (Long internally) | Never fabricate IDs; always reuse from `createVertex` return value |
| `explainSuspicion` N×M calls per identifier type | Bounded by `ALL_IDENTIFIER_EDGE_LABELS.size = 4`; acceptable for example scope |
| PageRank iterates over all vertex types | Service-layer post-filter to `User`; identifier vertices don't dominate because they have low fan-in |
| Blueprint neo4j-java-driver version needs pin | Managed by `bluetape4k-graph-bom` platform; no explicit version needed |

---

## 13. DoD (Definition of Done)

- [ ] `graph/abuser-detection/` module created with all files per §7
- [ ] `settings.gradle.kts` and `gradle/libs.versions.toml` updated
- [ ] `AbuserDetectionSchema.kt` with all 5 vertex labels and 5 edge labels
- [ ] `AbuserDetectionService` + `AbuserDetectionSuspendService` implement all methods
- [ ] `AbstractAbuserDetectionTest` covers all 7 test cases
- [ ] `AbstractAbuserDetectionSuspendTest` covers same 7 cases with `runTest`
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
| **Round 1 final** | **All reviewers** | **0** | **0** | impl-phase | low | **PROCEED** |

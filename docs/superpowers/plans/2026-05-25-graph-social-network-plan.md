# Plan: graph/social-network Workshop Module

**Date**: 2026-05-25
**Branch**: `feat/graph-social-network`
**Spec**: `docs/superpowers/specs/2026-05-25-graph-social-network-design.md`
**Module**: `graph/social-network` — Gradle module name: `graph-social-network`
**Canonical Pattern**: `graph/abuser-detection/`
**Stack**: Kotlin 2.3.20, Java 25, bluetape4k 1.5.0-Beta2, bluetape4k-graph (via BOM)

---

## Key Spec Decisions Encoded in Tasks

1. **Auto-registration**: `settings.gradle.kts` line 27 already has `includeModules("graph", false, true)`. The `includeModules` function auto-discovers all subdirectories under `graph/`. Creating `graph/social-network/build.gradle.kts` is sufficient — no settings.gradle.kts changes needed. The resulting module name is `:graph-social-network`.

2. **KNOWS bidirectional**: `connect(A,B)` creates TWO directed edges (A→B, B→A) with IDENTICAL properties. Callers must NOT call `connect(B,A)` after `connect(A,B)`.

3. **find-or-create idempotency**: `addPerson`/`addCompany` find by domain key (`personId`/`companyId`) only. Second call with same key returns existing vertex without updating properties.

4. **Seed centralization**: `@BeforeEach` in AbstractSocialNetworkTest calls `seedSocialNetwork(service)` after dropGraph + initialize. This reduces boilerplate across 29 test cases that all need the same topology.

5. **FOAF tie-breaking**: personId domain key ascending (NOT backend GraphElementId) for deterministic cross-backend ordering.

6. **neighbors() seed exclusion**: All algorithms (FOAF, N-degree, findColleagues) must explicitly subtract `{seed}` from results. `neighbors()` does not guarantee seed exclusion at depth >= 2.

7. **Suspend service returns List, not Flow**: FOAF sorting requires full collection. All query methods in `SocialNetworkSuspendService` collect `Flow<GraphVertex>.toList()` internally and return `List`.

8. **Unique graphName per concrete test class**: Prevents state collision when multiple test classes share a Neo4j/Memgraph container.

9. **ID-based set operations**: `GraphVertex` equality is NOT guaranteed to be ID-based. Always compare via `.id` using `Set<GraphElementId>`.

10. **Validation policy**: `requireNotBlank()` only on domain keys (`personId`, `companyId`, `role`). Optional metadata allows empty string. Numeric: `strength in 1..10`, `degree in 1..MAX_TRAVERSAL_DEPTH`, `limit > 0`.

---

## Phase 1 — Module Scaffolding

### T1: Module Registration and Build Configuration

- **Complexity**: low
- **File**: `graph/social-network/build.gradle.kts`
- **Dependencies**: None
- **Notes**:
  - No `settings.gradle.kts` changes needed — `includeModules("graph", false, true)` auto-discovers subdirectories.
  - Copy `graph/abuser-detection/build.gradle.kts` structure exactly.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform {
        includeTags("integration")
    }
    jvmArgs = tasks.test.get().jvmArgs
}
```

  - Dependencies (order matters — BOM must be first):
    - `implementation(platform(libs.bluetape4k.graph.bom))`
    - `implementation(libs.bluetape4k.graph.core)` + `implementation(libs.bluetape4k.graph.tinkerpop)`
    - `compileOnly(libs.bluetape4k.graph.neo4j)` + `compileOnly(libs.bluetape4k.graph.memgraph)`
    - `implementation(libs.bluetape4k.logging)` + `implementation(libs.bluetape4k.coroutines)`
    - `implementation(libs.kotlinx.coroutines.core.lib)` + `testImplementation(libs.kotlinx.coroutines.test.lib)`
    - `testImplementation(project(":shared"))` + `testImplementation(libs.bluetape4k.junit5)` + `testImplementation(libs.bluetape4k.testcontainers)` + `testImplementation(libs.testcontainers.neo4j)` + `testImplementation(libs.bluetape4k.assertions)` + `testImplementation(libs.mockk)`
  - **Gotcha**: `.get()` on configurations is REQUIRED in Kotlin DSL.
  - **Verify**: `./gradlew :graph-social-network:dependencies` runs without error.

---

### T2: Test Resources

- **Complexity**: low
- **Files**:
  - `graph/social-network/src/test/resources/junit-platform.properties`
  - `graph/social-network/src/test/resources/logback-test.xml`
- **Dependencies**: T1

- `junit-platform.properties` (exact from abuser-detection):
```properties
junit.jupiter.extensions.autodetection.enabled=true
junit.jupiter.testinstance.lifecycle.default=per_class

junit.jupiter.execution.parallel.enabled=false
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```
  - **NEVER add** `junit.jupiter.tags.exclude=integration` here — tag exclusion is done only in `build.gradle.kts tasks.test { excludeTags }`.

- `logback-test.xml` — copy from abuser-detection, change logger:
```xml
<logger name="io.bluetape4k.workshop.graph.social" level="DEBUG"/>
```

---

## Phase 2 — Schema and Domain Model

### T3: Schema Definition

- **Complexity**: low
- **File**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/schema/SocialNetworkSchema.kt`
- **Dependencies**: T1
- **Notes**:
  - Follow abuser-detection `AbuserDetectionSchema.kt` pattern exactly.
  - Graph properties are `Map<String, String>` — even `strength: Int` and `isCurrent: Boolean` are stored as strings. Conversion at service layer.

```kotlin
package io.bluetape4k.workshop.graph.social.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

/** Person vertex label for social network. */
object PersonLabel : VertexLabel("Person") {
    val personId = string("personId")
    val name = string("name")
    val title = string("title")
    val location = string("location")
}

/** Company vertex label for social network. */
object CompanyLabel : VertexLabel("Company") {
    val companyId = string("companyId")
    val name = string("name")
    val industry = string("industry")
    val location = string("location")
}

/** Bidirectional KNOWS edge between two Person vertices. */
object KnowsLabel : EdgeLabel("KNOWS", PersonLabel, PersonLabel) {
    val since = string("since")
    val strength = string("strength")   // stored as String, valid values "1".."10"
}

/** WORKS_AT edge from Person to Company. */
object WorksAtLabel : EdgeLabel("WORKS_AT", PersonLabel, CompanyLabel) {
    val role = string("role")
    val startDate = string("startDate")
    val isCurrent = string("isCurrent") // stored as String "true"/"false"
}

/** Unidirectional FOLLOWS edge between two Person vertices. */
object FollowsLabel : EdgeLabel("FOLLOWS", PersonLabel, PersonLabel)
```

---

### T4: Domain Model — ConnectionRecommendation

- **Complexity**: low
- **File**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/model/ConnectionRecommendation.kt`
- **Dependencies**: T3

```kotlin
package io.bluetape4k.workshop.graph.social.model

import io.bluetape4k.graph.model.GraphVertex
import java.io.Serializable

/**
 * FOAF recommendation result containing the recommended person and mutual connection details.
 *
 * @param person the recommended person vertex
 * @param mutualConnectionCount number of shared direct connections with the seed
 * @param mutualConnections the shared direct connection vertices
 */
data class ConnectionRecommendation(
    val person: GraphVertex,
    val mutualConnectionCount: Int,
    val mutualConnections: List<GraphVertex>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
```

---

## Phase 3 — Service Implementation

### T5: SocialNetworkService (Blocking)

- **Complexity**: high
- **File**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/service/SocialNetworkService.kt`
- **Dependencies**: T3, T4

**Key patterns**:

```kotlin
class SocialNetworkService(
    private val ops: GraphOperations,
    private val graphName: String = "social_network",
) {
    companion object : KLogging() {
        /** Maximum allowed traversal depth for degree-based and path-based queries. */
        const val MAX_TRAVERSAL_DEPTH: Int = 6
    }

    fun initialize() {
        if (!ops.graphExists(graphName)) {
            ops.createGraph(graphName)
        }
    }

    // find-or-create pattern
    fun addPerson(personId: String, name: String, title: String = "", location: String = ""): GraphVertex {
        personId.requireNotBlank("personId")
        return ops.findVerticesByLabel(PersonLabel.label, mapOf(PersonLabel.personId.name to personId))
            .firstOrNull()
            ?: ops.createVertex(
                PersonLabel.label,
                mapOf(
                    PersonLabel.personId.name to personId,
                    PersonLabel.name.name to name,
                    PersonLabel.title.name to title,
                    PersonLabel.location.name to location,
                )
            )
    }

    // connect() — TWO directed edges, SAME properties on both
    fun connect(personVertexId1: GraphElementId, personVertexId2: GraphElementId, since: String = "", strength: Int = 5) {
        require(strength in 1..10) { "strength must be in 1..10, got $strength" }
        val props = mapOf(
            KnowsLabel.since.name to since,
            KnowsLabel.strength.name to strength.toString(),
        )
        ops.createEdge(personVertexId1, personVertexId2, KnowsLabel.label, props)
        ops.createEdge(personVertexId2, personVertexId1, KnowsLabel.label, props)  // same props
    }

    fun getConnectionsWithinDegree(personVertexId: GraphElementId, degree: Int): List<GraphVertex> {
        require(degree in 1..MAX_TRAVERSAL_DEPTH) { "degree must be in 1..$MAX_TRAVERSAL_DEPTH, got $degree" }
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree))
            .filter { it.id != personVertexId }  // explicit seed exclusion — neighbors() does not guarantee this
    }

    fun getNthDegreeConnections(personVertexId: GraphElementId, degree: Int): List<GraphVertex> {
        require(degree in 1..MAX_TRAVERSAL_DEPTH) { "degree must be in 1..$MAX_TRAVERSAL_DEPTH, got $degree" }
        val allWithin = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree))
            .filter { it.id != personVertexId }
        val closerIds = if (degree > 1) {
            ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree - 1))
                .map { it.id }.toSet()
        } else emptySet()
        return allWithin.filter { it.id !in closerIds }
    }

    // FOAF — spec section 6.1
    fun recommendConnections(personVertexId: GraphElementId, limit: Int = 10): List<ConnectionRecommendation> {
        limit.requirePositiveNumber("limit")
        val directFriends = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
        val directFriendIds = directFriends.map { it.id }.toSet()
        val foafCandidates = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 2))
            .filter { it.id != personVertexId && it.id !in directFriendIds }
            .distinctBy { it.id }  // depth=2 traversal may return duplicates via different paths
        val recommendations = foafCandidates.map { candidate ->
            val candidateFriendIds = ops.neighbors(candidate.id, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
                .map { it.id }.toSet()
            val mutuals = directFriends.filter { it.id in candidateFriendIds }
            ConnectionRecommendation(candidate, mutuals.size, mutuals)
        }
        return recommendations
            .sortedWith(
                compareByDescending<ConnectionRecommendation> { it.mutualConnectionCount }
                    .thenBy { it.person.properties[PersonLabel.personId.name] ?: "" }  // personId for determinism
            )
            .take(limit)
    }

    fun findColleagues(personVertexId: GraphElementId): List<GraphVertex> {
        val companies = ops.neighbors(personVertexId, NeighborOptions(WorksAtLabel.label, Direction.OUTGOING, 1))
        return companies.flatMap { company ->
            ops.neighbors(company.id, NeighborOptions(WorksAtLabel.label, Direction.INCOMING, 1))
        }
            .filter { it.id != personVertexId }
            .distinctBy { it.id }
    }
}
```

**Critical gotchas**:
- `connect()`: SAME `props` map for both edges (property symmetry).
- FOAF: `distinctBy { it.id }` on candidates — depth=2 traversal returns duplicates via different paths.
- Tie-breaking: `personId` from `properties` map, NOT `GraphElementId`.
- `findColleagues`: `distinctBy { it.id }` — same person at multiple companies returns duplicates without dedup.

---

### T6: SocialNetworkSuspendService (Suspend)

- **Complexity**: high
- **File**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/service/SocialNetworkSuspendService.kt`
- **Dependencies**: T5

**Key differences from T5**:
- `companion object : KLoggingChannel()` (NOT `KLogging()`).
- All methods have `suspend` keyword.
- `ops.neighbors()` returns `Flow<GraphVertex>` → must call `.toList()`.
- `ops.findVerticesByLabel()` returns `Flow<GraphVertex>` → use `import kotlinx.coroutines.flow.firstOrNull`.
- Never use `runCatching {}` around suspend calls — CancellationException must propagate.

```kotlin
class SocialNetworkSuspendService(
    private val ops: GraphSuspendOperations,
    private val graphName: String = "social_network",
) {
    companion object : KLoggingChannel() {
        const val MAX_TRAVERSAL_DEPTH: Int = 6
    }

    suspend fun addPerson(personId: String, name: String, title: String = "", location: String = ""): GraphVertex {
        personId.requireNotBlank("personId")
        return ops.findVerticesByLabel(PersonLabel.label, mapOf(PersonLabel.personId.name to personId))
            .firstOrNull()  // import kotlinx.coroutines.flow.firstOrNull
            ?: ops.createVertex(PersonLabel.label, mapOf(...))
    }

    suspend fun getDirectConnections(personVertexId: GraphElementId): List<GraphVertex> {
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
            .toList()  // collect Flow<GraphVertex>
    }
    // ... all other methods mirror T5 logic with suspend + .toList()
}
```

---

## Phase 4 — Test Infrastructure

### T7: SocialNetworkSeed

- **Complexity**: medium
- **File**: `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/seed/SocialNetworkSeed.kt`
- **Dependencies**: T5, T6

```kotlin
data class SocialNetworkSeed(
    val alice: GraphVertex,
    val bob: GraphVertex,
    val carol: GraphVertex,
    val dave: GraphVertex,
    val eve: GraphVertex,
    val frank: GraphVertex,
    val grace: GraphVertex,
    val bluetape4k: GraphVertex,
    val acme: GraphVertex,
)

fun seedSocialNetwork(service: SocialNetworkService): SocialNetworkSeed {
    // 7 persons with personId domain keys
    val alice = service.addPerson("alice", "Alice", "Engineer", "Seoul")
    val bob   = service.addPerson("bob", "Bob", "Manager", "Seoul")
    val carol = service.addPerson("carol", "Carol", "Designer", "Busan")
    val dave  = service.addPerson("dave", "Dave", "Engineer", "Incheon")
    val eve   = service.addPerson("eve", "Eve", "Analyst", "Seoul")
    val frank = service.addPerson("frank", "Frank", "Engineer", "Daejeon")
    val grace = service.addPerson("grace", "Grace", "PM", "Seoul")

    // 2 companies
    val bluetape4k = service.addCompany("bluetape4k", "Bluetape4k", "Technology", "Seoul")
    val acme       = service.addCompany("acme", "Acme", "Manufacturing", "Incheon")

    // KNOWS edges — call connect() ONCE per pair (creates BOTH directions)
    service.connect(alice.id, bob.id,   since = "2020-01-01", strength = 8)
    service.connect(bob.id, carol.id,   since = "2021-03-15", strength = 6)
    service.connect(alice.id, frank.id, since = "2019-06-01", strength = 7)
    service.connect(bob.id, dave.id,    since = "2022-02-01", strength = 5)
    service.connect(dave.id, grace.id,  since = "2023-01-01", strength = 4)

    // FOLLOWS — unidirectional only
    service.follow(carol.id, eve.id)

    // WORKS_AT — note Carol is isCurrent=false (past employee, for colleague test)
    service.addWorkExperience(alice.id, bluetape4k.id, "Senior Engineer", "2020-01-01", isCurrent = true)
    service.addWorkExperience(bob.id,   bluetape4k.id, "Manager",        "2019-06-01", isCurrent = true)
    service.addWorkExperience(carol.id, bluetape4k.id, "Designer",       "2021-01-01", isCurrent = false)
    service.addWorkExperience(dave.id,  acme.id,       "Engineer",       "2022-01-01", isCurrent = true)

    return SocialNetworkSeed(alice, bob, carol, dave, eve, frank, grace, bluetape4k, acme)
}

// Suspend overload — identical logic
suspend fun seedSocialNetwork(service: SocialNetworkSuspendService): SocialNetworkSeed { ... }
```

**Topology verification**:
- Alice 1-degree: {Bob, Frank}
- Alice 2-degree (FOAF): {Carol, Dave} — both mutual count = 1 (via Bob)
- Alice 3-degree: {Grace} — NOT in FOAF candidates (depth=3)
- Carol→Eve: FOLLOWS only (not KNOWS) — Eve absent from Carol's `getDirectConnections`
- Alice colleagues: {Bob, Carol} at Bluetape4k (Carol isCurrent=false tests past employee inclusion)

---

### T8: AbstractSocialNetworkTest (Blocking)

- **Complexity**: high
- **File**: `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/AbstractSocialNetworkTest.kt`
- **Dependencies**: T5, T7

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSocialNetworkTest {
    protected abstract val graphName: String
    protected abstract val ops: GraphOperations
    protected abstract val service: SocialNetworkService

    protected lateinit var seed: SocialNetworkSeed

    @BeforeEach
    fun setUp() {
        runCatching { ops.dropGraph(graphName) }  // first run: no graph to drop
        service.initialize()
        seed = seedSocialNetwork(service)
    }
```

**29 test cases** (see spec section 8.5):

| # | Category | Test | Assertion |
|---|----------|------|-----------|
| 1 | Vertex | `addPerson creates a new Person vertex` | `label == PersonLabel.label`; properties check |
| 2 | Vertex | `addPerson returns existing vertex on second call (idempotent)` | `first.id == second.id` |
| 3 | Vertex | `addCompany creates a new Company vertex` | label + properties check |
| 4 | Edge | `connect creates bidirectional KNOWS edges` | A→B exists; B→A exists |
| 5 | Edge | `connect creates edges with identical properties on both directions` | both have same `since`, `strength` |
| 6 | Edge | `follow creates unidirectional FOLLOWS edge` | Carol→Eve edge exists |
| 7 | Edge | `follow does not create reverse FOLLOWS edge` | Eve→Carol FOLLOWS does NOT exist |
| 8 | Validation | `addPerson throws on blank personId` | `assertFailsWith<IllegalArgumentException>` |
| 9 | Validation | `getConnectionsWithinDegree throws on degree out of range` | degree=0 and degree=7 throw |
| 10 | Validation | `recommendConnections throws on non-positive limit` | limit=0 throws |
| 11 | Query | `getDirectConnections returns 1st degree connections` | Alice → names contain "Bob", "Frank" |
| 12 | Query | `getDirectConnections does not include FOLLOWS targets` | Carol's result does NOT contain Eve |
| 13 | Query | `getConnectionsWithinDegree returns up to Nth degree` | Alice degree=2 → {Bob, Frank, Carol, Dave} |
| 14 | Query | `getNthDegreeConnections returns exactly Nth degree` | Alice N=2 → {Carol, Dave} (not Bob, Frank) |
| 15 | Query | `getNthDegreeConnections with degree 1 matches direct connections` | same as `getDirectConnections` |
| 16 | Path | `findConnectionPath returns shortest path` | Alice→Carol path length=2 |
| 17 | Path | `findConnectionPath returns null for disconnected vertices` | Frank→Eve returns null |
| 18 | Path | `findAllConnectionPaths returns all paths within depth` | Alice→Carol paths.size >= 1 |
| 19 | Mutual | `findMutualConnections returns shared connections` | Alice-Carol mutual contains "Bob" |
| 20 | Mutual | `findMutualConnections returns empty for no shared connections` | Alice-Eve returns empty |
| 21 | FOAF | `recommendConnections returns FOAF candidates with mutual connections` | Alice → contains Carol({Bob}), Dave({Bob}) |
| 22 | FOAF | `recommendConnections excludes direct connections` | Alice → does NOT contain Bob, Frank |
| 23 | FOAF | `recommendConnections excludes self` | Alice → does NOT contain Alice |
| 24 | FOAF | `recommendConnections excludes depth-3+ connections` | Alice → does NOT contain Grace |
| 25 | Colleague | `findColleagues returns coworkers at same company` | Alice → contains Bob, Carol |
| 26 | Colleague | `findColleagues excludes self` | Alice → does NOT contain Alice |
| 27 | Colleague | `findColleagues includes past employees (isCurrent=false)` | Carol (past) IS in Alice's colleagues |
| 28 | Lifecycle | `initialize is idempotent` | two calls without exception |
| 29 | Error | `query methods with nonexistent vertexId return empty result or null` | empty List or null path |

**Key assertion patterns**:
- Use `shouldContainAll` / `shouldNotContain` for set-like checks.
- FOAF #21: use `containsExactlyInAnyOrder` (both Carol+Dave have mutualCount=1, order may vary).
- Names: `results.map { it.properties[PersonLabel.name.name] }`.
- `assertFailsWith<IllegalArgumentException>` for validation (NOT `assertThrows`, NOT `invoking/shouldThrow`).

---

### T9: AbstractSocialNetworkSuspendTest (Suspend)

- **Complexity**: high
- **File**: `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/AbstractSocialNetworkSuspendTest.kt`
- **Dependencies**: T6, T7
- **Notes**: Mirror of T8 with `runSuspendIO { }` wrapping every test body. Same 29 test cases.

```kotlin
@BeforeEach
fun setUp() = runSuspendIO {
    runCatching { ops.dropGraph(graphName) }
    service.initialize()
    seed = seedSocialNetwork(service)  // suspend overload
}

@Test
fun `getDirectConnections returns 1st degree connections`() = runSuspendIO {
    val connections = service.getDirectConnections(seed.alice.id)
    val names = connections.map { it.properties[PersonLabel.name.name] }
    names shouldContainAll listOf("Bob", "Frank")
}
```

---

## Phase 5 — Concrete Test Classes

### T10: TinkerGraph Concrete Tests

- **Complexity**: low
- **Files**:
  - `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/SocialNetworkTinkerGraphTest.kt`
  - `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/SocialNetworkSuspendTinkerGraphTest.kt`
- **Dependencies**: T8, T9

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialNetworkTinkerGraphTest : AbstractSocialNetworkTest() {
    companion object : KLogging()

    override val graphName = "test_social_tinkergraph"
    override val ops: GraphOperations = TinkerGraphOperations()
    override val service = SocialNetworkService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialNetworkSuspendTinkerGraphTest : AbstractSocialNetworkSuspendTest() {
    companion object : KLoggingChannel()

    override val graphName = "test_social_tinkergraph_suspend"
    override val ops: GraphSuspendOperations = TinkerGraphSuspendOperations()
    override val service = SocialNetworkSuspendService(ops, graphName)

    @AfterAll
    fun tearDown() {
        ops.close()
    }
}
```

---

### T11: Neo4j Concrete Tests

- **Complexity**: low
- **Files**:
  - `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/Neo4jSocialNetworkTest.kt`
  - `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/Neo4jSocialNetworkSuspendTest.kt`
- **Dependencies**: T8, T9

```kotlin
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jSocialNetworkTest : AbstractSocialNetworkTest() {
    companion object : KLogging() {
        private val neo4j = Neo4jServer.Launcher.neo4j
    }

    override val graphName = "test_social_neo4j"

    private val driver: Driver by lazy {
        GraphDatabase.driver(neo4j.url)
    }

    override val ops: GraphOperations by lazy {
        Neo4jGraphOperations(driver)
    }

    override val service by lazy {
        SocialNetworkService(ops, graphName)
    }

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
            .onFailure { log.warn(it) { "Driver close failed" } }
    }
}
```

- **Gotcha**: `Neo4jGraphOperations(driver)` — second arg is Bolt database name, NOT our logical graphName.
- No `@Testcontainers` annotation needed with Launcher singleton.

---

### T12: Memgraph Concrete Tests

- **Complexity**: low
- **Files**:
  - `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/MemgraphSocialNetworkTest.kt`
  - `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/MemgraphSocialNetworkSuspendTest.kt`
- **Dependencies**: T8, T9
- **Notes**: Same structure as T11 but:
  - `MemgraphServer.Launcher.memgraph`
  - `GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())` — Memgraph uses no auth
  - `MemgraphGraphOperations(driver)` / `MemgraphGraphSuspendOperations(driver)`
  - graphName: `"test_social_memgraph"` / `"test_social_memgraph_suspend"`

---

## Phase 6 — Verification

### T13: TinkerGraph Test Execution

- **Complexity**: low
- **Dependencies**: T10
- **Command**: `./gradlew :graph-social-network:test`
- **Expected**: 58 tests pass (29 blocking + 29 suspend)
- **Common failures**:
  - `neighbors()` returns seed at depth >= 2 — ensure all algorithms filter `{ it.id != personVertexId }`
  - Graph property values are always String — comparison must account for this
  - FOAF candidates must use `distinctBy { it.id }` — depth-2 traversal returns duplicates

### T14: Integration Test Execution

- **Complexity**: medium
- **Dependencies**: T11, T12
- **Command**: `./gradlew :graph-social-network:integrationTest`
- **Expected**: 116 tests pass (29 × 4 backends × 2 service styles)
- **Notes**: Requires Docker. Container startup ~30-60s on first run.

---

## Phase 7 — Documentation

### T15: README Files

- **Complexity**: medium
- **Files**: `graph/social-network/README.md`, `graph/social-network/README.ko.md`
- **Dependencies**: T13
- **Structure**: Module title → Architecture → Core Features → Graph Topology → Usage → Build commands → Stack

### T16: Architecture Diagram

- **Complexity**: low
- **Files**:
  - `graph/social-network/docs/images/readme-diagrams/social-network-architecture.svg`
  - `graph/social-network/docs/images/readme-diagrams/social-network-architecture.png`
- **Dependencies**: T15
- **Notes**: Invoke `bluetape4k-diagram` skill before generating. Embed only PNG in README.

---

## Complete File Inventory

| Path | Complexity | Phase |
|------|-----------|-------|
| `graph/social-network/build.gradle.kts` | low | 1 |
| `graph/social-network/src/test/resources/junit-platform.properties` | low | 1 |
| `graph/social-network/src/test/resources/logback-test.xml` | low | 1 |
| `src/main/.../schema/SocialNetworkSchema.kt` | low | 2 |
| `src/main/.../model/ConnectionRecommendation.kt` | low | 2 |
| `src/main/.../service/SocialNetworkService.kt` | **high** | 3 |
| `src/main/.../service/SocialNetworkSuspendService.kt` | **high** | 3 |
| `src/test/.../seed/SocialNetworkSeed.kt` | medium | 4 |
| `src/test/.../AbstractSocialNetworkTest.kt` | **high** | 4 |
| `src/test/.../AbstractSocialNetworkSuspendTest.kt` | **high** | 4 |
| `src/test/.../SocialNetworkTinkerGraphTest.kt` | low | 5 |
| `src/test/.../SocialNetworkSuspendTinkerGraphTest.kt` | low | 5 |
| `src/test/.../Neo4jSocialNetworkTest.kt` | low | 5 |
| `src/test/.../Neo4jSocialNetworkSuspendTest.kt` | low | 5 |
| `src/test/.../MemgraphSocialNetworkTest.kt` | low | 5 |
| `src/test/.../MemgraphSocialNetworkSuspendTest.kt` | low | 5 |
| `graph/social-network/README.md` | medium | 7 |
| `graph/social-network/README.ko.md` | medium | 7 |
| `docs/images/readme-diagrams/social-network-architecture.svg` | low | 7 |
| `docs/images/readme-diagrams/social-network-architecture.png` | low | 7 |

---

## Critical Gotchas Summary

1. **No settings.gradle.kts change** — `includeModules("graph", ...)` auto-discovers subdirectories.
2. **ID-based set operations** — never use `List.minus()` on `GraphVertex`. Always compare via `.id`.
3. **neighbors() seed exclusion** — filter `{ it.id != seedVertexId }` on ALL depth >= 2 queries.
4. **FOAF tie-breaking** — use `personId` domain key from properties, not `GraphElementId`.
5. **connect() creates BOTH directions** — never call `connect(B, A)` after `connect(A, B)`.
6. **Graph properties are all Strings** — `strength` stored as `"5"`, `isCurrent` as `"true"`.
7. **Neo4j/Memgraph ops constructor** — do NOT pass graphName as second arg (that's the Bolt database name).
8. **suspend findVerticesByLabel returns Flow** — must use `kotlinx.coroutines.flow.firstOrNull`.
9. **configurations `.get()` required** in Kotlin DSL for `extendsFrom`.
10. **BOM platform import must be first** for version-less aliases to resolve.

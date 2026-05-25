# graph/recommendation 모듈 구현 계획
_Date: 2026-05-25 | Issue #13_
_Spec: docs/superpowers/specs/2026-05-25-graph-recommendation-design.md_

---

## Task List

### T1 — build.gradle.kts
**complexity: low**
**File**: `graph/recommendation/build.gradle.kts`

Knowledge-graph 패턴 그대로 복사 후 모듈명만 변경. 핵심:
- `testImplementation.extendsFrom(compileOnly, runtimeOnly)`
- `excludeTags("integration")` on `tasks.test`
- `integrationTest` task 등록
- `libs.testcontainers.neo4j` — Neo4jContainer supertype 필요

**Acceptance**:
- `./gradlew :graph-recommendation:compileKotlin` 성공
- `./gradlew :graph-recommendation:tasks` 목록에 `:graph-recommendation` 확인 (settings.gradle.kts 자동 등록 검증)
- `graph/recommendation/build.gradle.kts` 존재

> **Note**: `settings.gradle.kts`의 `includeModules("graph", ...)` 가 `graph/` 하위 디렉토리를 자동 등록. 디렉토리명이 `recommendation`이면 `:graph-recommendation`으로 등록됨. 명시적 등록 불필요하나 위 acceptance로 검증.

---

### T2 — RecommendationSchema.kt + RecommendationConstants.kt
**complexity: low**
**Files**:
- `src/main/kotlin/io/bluetape4k/workshop/graph/recommendation/schema/RecommendationSchema.kt`
- `src/main/kotlin/io/bluetape4k/workshop/graph/recommendation/RecommendationConstants.kt`

```kotlin
package io.bluetape4k.workshop.graph.recommendation.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

/** Vertex: User (buyer / follower) */
object UserLabel : VertexLabel("User") {
    val userId = string("userId")
    val name = string("name")
}

/** Vertex: Product (purchasable item) */
object ProductLabel : VertexLabel("Product") {
    val productId = string("productId")
    val name = string("name")
    val category = string("category")
}

/** Edge: User --PURCHASED--> Product */
object PurchasedLabel : EdgeLabel("PURCHASED", UserLabel, ProductLabel) {
    val rating = string("rating")       // "1"-"5"; absent when not rated
    val purchasedAt = string("purchasedAt")  // ISO-8601; absent when not set
}

/** Edge: User --FOLLOWS--> User (unidirectional) */
object FollowsLabel : EdgeLabel("FOLLOWS", UserLabel, UserLabel)
```

English KDoc on each object.

```kotlin
// RecommendationConstants.kt
package io.bluetape4k.workshop.graph.recommendation

/** Maximum number of recommendations returned by any recommend* function. */
const val MAX_RECOMMENDATION_LIMIT: Int = 100
```

> **Rationale**: 단일 패키지 수준 상수 — `RecommendationService`와 `RecommendationSuspendService` 양쪽에서 import해 사용. 두 클래스 내 companion에 중복 선언 시 drift 위험.

**Acceptance**: 컴파일 성공, object 이름/label 문자열 스펙과 일치. `MAX_RECOMMENDATION_LIMIT` 패키지 상수로 존재.

---

### T3 — Model Classes
**complexity: low**
**Files**:
- `src/main/kotlin/.../recommendation/model/ProductRecommendation.kt`
- `src/main/kotlin/.../recommendation/model/FollowRecommendation.kt`

```kotlin
// ProductRecommendation.kt
data class ProductRecommendation(
    val product: GraphVertex,
    /** Count of distinct users who bought both [product] and the seed user's products. */
    val score: Int,
    /** Distinct co-buyers that drove the score. */
    val sharedBuyers: List<GraphVertex>,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L
    }
}

// FollowRecommendation.kt
data class FollowRecommendation(
    val person: GraphVertex,
    /**
     * Count of people the seed user follows who also follow [person].
     * These are FOAF intermediaries, not "mutual follows" in the symmetric sense.
     */
    val mutualFollowCount: Int,
    val mutualFollows: List<GraphVertex>,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L
    }
}
```

**Acceptance**: 컴파일, data class equality, Serializable 검증. English KDoc on all public API members (class, all public constructor parameters).

---

### 🔴 Pre-Implementation Gate — bluetape4k-patterns (T4/T8 전 필수)

T4와 T8 구현 시작 전 `Skill("bluetape4k-patterns")` 명시 호출 필수.
점검 항목: KLogging/KLoggingChannel, requireNotBlank/requirePositiveNumber, Serializable+serialVersionUID, `close()` 독립 runCatching, suspend에서 CancellationException 재throw, 테스트 assertFailsWith/coInvoking/runTest.

---

### T4 — RecommendationService.kt (Blocking)
**complexity: high**
**File**: `src/main/kotlin/.../recommendation/service/RecommendationService.kt`

> **Edge 중복 계약**: `purchase()`와 `follow()`는 중복 edge를 허용 (멱등성 없음). addUser/addProduct의 find-or-create 패턴과 다름 — 동일 쌍에 대해 여러 번 호출 시 edge가 누적됨. 추천 점수에 영향 없음 (co-buyer Set으로 dedup).
>
> **Non-existent vertex 계약**: `recommendProducts()`/`recommendFollows()` 에서 존재하지 않는 `userVertexId`를 넘기면 `ops.neighbors()`가 emptyList를 반환하므로 early return → `emptyList()` 반환. 별도 pre-check 불필요.

구현 상세:

#### initialize()
```kotlin
fun initialize() {
    if (!ops.graphExists(graphName)) {
        ops.createGraph(graphName)
    }
}
```

#### addUser() / addProduct() — find-or-create
```kotlin
fun addUser(userId: String, name: String): GraphVertex {
    userId.requireNotBlank("userId")
    name.requireNotBlank("name")
    return ops.findVerticesByLabel(UserLabel.label, mapOf(UserLabel.userId.name to userId))
        .firstOrNull() ?: ops.createVertex(UserLabel.label, mapOf(
            UserLabel.userId.name to userId,
            UserLabel.name.name to name,
        ))
}
// addProduct: 동일 패턴, category="" 시 빈 string 저장
```

#### purchase()
```kotlin
fun purchase(
    userVertexId: GraphElementId,
    productVertexId: GraphElementId,
    rating: Int = 0,
    purchasedAt: String = "",
): GraphEdge {
    rating.requireInRange(0, 5, "rating")
    val props = buildMap {
        if (rating > 0) put(PurchasedLabel.rating.name, rating.toString())
        if (purchasedAt.isNotBlank()) put(PurchasedLabel.purchasedAt.name, purchasedAt)
    }
    return ops.createEdge(userVertexId, productVertexId, PurchasedLabel.label, props)
}
```

#### follow()
```kotlin
fun follow(followerVertexId: GraphElementId, followeeVertexId: GraphElementId): GraphEdge {
    require(followerVertexId != followeeVertexId) {
        "followerVertexId must differ from followeeVertexId"
    }
    return ops.createEdge(followerVertexId, followeeVertexId, FollowsLabel.label, emptyMap())
}
```

#### recommendProducts() — Collaborative Filtering
```kotlin
/**
 * Returns products bought by co-buyers of the seed user's products,
 * ranked by distinct co-buyer count (score).
 *
 * **N+1 warning**: issues one neighbor query per product and one per co-buyer.
 * For large graphs, prefer native Cypher/Gremlin queries.
 *
 * @param userVertexId GraphVertex.id returned by [addUser]
 * @param limit 1..MAX_RECOMMENDATION_LIMIT
 * @return emptyList() when [userVertexId] not found or has no purchases
 */
fun recommendProducts(userVertexId: GraphElementId, limit: Int = 10): List<ProductRecommendation> {
    limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

    val myProducts = ops.neighbors(userVertexId,
        NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1))
    if (myProducts.isEmpty()) return emptyList()
    val myProductIds = myProducts.map { it.id }.toSet()

    // candidateMap: candidateProductId → (candidateVertex, Set<coBuyerVertex>)
    val candidateMap = mutableMapOf<GraphElementId, Pair<GraphVertex, MutableSet<GraphVertex>>>()

    for (product in myProducts) {
        val coBuyers = ops.neighbors(product.id,
            NeighborOptions(PurchasedLabel.label, Direction.INCOMING, 1))
            .filter { it.id != userVertexId }

        for (coBuyer in coBuyers) {
            val theirProducts = ops.neighbors(coBuyer.id,
                NeighborOptions(PurchasedLabel.label, Direction.OUTGOING, 1))
                .filter { it.id !in myProductIds }

            for (candidate in theirProducts) {
                candidateMap.getOrPut(candidate.id) { candidate to mutableSetOf() }
                    .second += coBuyer
            }
        }
    }

    return candidateMap.values
        .map { (product, buyers) ->
            ProductRecommendation(product, buyers.size, buyers.toList())
        }
        .sortedWith(compareByDescending<ProductRecommendation> { it.score }
            .thenBy { it.product.properties[ProductLabel.productId.name]?.toString() ?: "" })
        .take(limit)
}
```

#### recommendFollows() — FOAF
```kotlin
/**
 * Recommends users to follow based on 2-hop FOLLOWS traversal.
 *
 * **N+1 warning**: issues one INCOMING neighbor query per depth-2 candidate.
 *
 * @param userVertexId GraphVertex.id returned by [addUser]
 * @param limit 1..MAX_RECOMMENDATION_LIMIT
 * @return emptyList() when [userVertexId] not found or has no follows
 */
fun recommendFollows(userVertexId: GraphElementId, limit: Int = 10): List<FollowRecommendation> {
    limit.requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")

    val myFollows = ops.neighbors(userVertexId,
        NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 1))
    if (myFollows.isEmpty()) return emptyList()
    val myFollowIds = myFollows.map { it.id }.toSet()

    val candidates = ops.neighbors(userVertexId,
        NeighborOptions(FollowsLabel.label, Direction.OUTGOING, 2))
        .filter { it.id != userVertexId && it.id !in myFollowIds }
        .distinctBy { it.id }

    return candidates
        .map { candidate ->
            val whoFollows = ops.neighbors(candidate.id,
                NeighborOptions(FollowsLabel.label, Direction.INCOMING, 1))
            val intermediaries = whoFollows.filter { it.id in myFollowIds }
            FollowRecommendation(candidate, intermediaries.size, intermediaries)
        }
        .filter { it.mutualFollowCount > 0 }
        .sortedWith(compareByDescending<FollowRecommendation> { it.mutualFollowCount }
            .thenBy { it.person.properties[UserLabel.userId.name]?.toString() ?: "" })
        .take(limit)
}
```

**Acceptance**: 단위 테스트에서 알고리즘 정확도 검증 (spec Section 6 예상값과 일치). English KDoc on all public API members (`initialize`, `addUser`, `addProduct`, `purchase`, `follow`, `recommendProducts`, `recommendFollows`).

---

### T5 — RecommendationSuspendService.kt (Coroutine)
**complexity: high**
**File**: `src/main/kotlin/.../recommendation/service/RecommendationSuspendService.kt`

- `GraphSuspendOperations` 기반 (blocking `GraphOperations` wrapping 금지)
- `companion object : KLoggingChannel()`
- `suspend fun` for mutators and initializer
- `recommendProducts`/`recommendFollows`는 `suspend fun` + `List<T>` 반환
  - 중간 연산은 `Flow<GraphVertex>.toList()` 사용
- 동일한 알고리즘 로직, 동일한 입력 검증

블로킹 서비스와 동일한 알고리즘 구조. `neighbors()`는 `Flow<GraphVertex>` 반환 → `.toList()` 수집 후 처리.

> **시퀀싱 노트**: T5는 T4의 blocking 구현을 diff 기준으로 작성. T4가 커밋된 이후 T5를 시작해야 안전 (Build Order 참조). 모든 `ops.neighbors(...)` 호출에 `suspend` 추가 + `Flow<GraphVertex>.toList()` 수집이 핵심 변경점.

**Acceptance**: suspend 서비스로 blocking 서비스와 동일한 추천 결과 생성. English KDoc on all public API members (동일 7개 함수).

---

### T6 — RecommendationSeed.kt
**complexity: medium**
**File**: `src/test/kotlin/.../recommendation/seed/RecommendationSeed.kt`

```kotlin
data class RecommendationSeed(
    // users
    val alice: GraphVertex,
    val bob: GraphVertex,
    val carol: GraphVertex,
    val dave: GraphVertex,
    val eve: GraphVertex,
    val frank: GraphVertex,
    // products
    val laptop: GraphVertex,
    val phone: GraphVertex,
    val tablet: GraphVertex,
    val headphones: GraphVertex,
    val keyboard: GraphVertex,
    val mouse: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

fun seedRecommendation(service: RecommendationService): RecommendationSeed { ... }
suspend fun seedRecommendation(service: RecommendationSuspendService): RecommendationSeed { ... }
```

도메인 키 정확한 string 값 (정렬 tie-break 테스트 결정요소):

```kotlin
// userId 값 — 알파벳 순서: alice < bob < carol < dave < eve < frank
const val USER_ALICE   = "alice"
const val USER_BOB     = "bob"
const val USER_CAROL   = "carol"
const val USER_DAVE    = "dave"
const val USER_EVE     = "eve"
const val USER_FRANK   = "frank"

// productId 값 — 알파벳 순서: headphones < keyboard < laptop < mouse < phone < tablet
const val PROD_HEADPHONES = "headphones"
const val PROD_KEYBOARD   = "keyboard"
const val PROD_LAPTOP     = "laptop"
const val PROD_MOUSE      = "mouse"
const val PROD_PHONE      = "phone"
const val PROD_TABLET     = "tablet"
```

이 정확한 값들이 `addUser(userId=...)` / `addProduct(productId=...)` 호출에 사용됨.
tie-break 정렬: keyboard < mouse (알파벳), dave < eve (알파벳) → 테스트 어설션 기준.

Seed topology (spec Section 6):
- 6 users, 6 products
- 18 PURCHASED edges (as per spec table)
- 12 FOLLOWS edges (as per spec table)

KDoc: expected recommendations for alice documented.

**Acceptance**: `seedRecommendation()` 실행 후 그래프에 12개 vertices + 30개 edges 존재.

---

### T7 — Test Resources
**complexity: low**
**Files**:
- `src/test/resources/junit-platform.properties`
- `src/test/resources/logback-test.xml`

knowledge-graph 모듈에서 복사. `logback-test.xml`은 패키지명 `recommendation`으로 조정.

**Acceptance**: 파일 존재, JUnit 5 설정 유효.

---

### T8 — AbstractRecommendationTest.kt
**complexity: high**
**File**: `src/test/kotlin/.../recommendation/AbstractRecommendationTest.kt`

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRecommendationTest {
    protected abstract val graphName: String
    protected abstract val ops: GraphOperations

    /** Concrete classes may override; default is lazy-initialized from [ops] and [graphName]. */
    protected open val service: RecommendationService by lazy { RecommendationService(ops, graphName) }

    protected lateinit var seed: RecommendationSeed

    @BeforeEach
    fun cleanGraph() {
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedRecommendation(service)
    }

    // Test groups (spec Section 9):
    // 1. Vertex mutators: addUser, addProduct (create + idempotent)
    // 2. Input validation: blank fields, rating > 5, limit = 0, limit = 101, self-follow
    // 3. Edge mutators: purchase, follow
    // 4. recommendProducts:
    //    - alice→headphones(3)/keyboard(1)/mouse(1) with correct ordering
    //    - user with no purchases → emptyList
    //    - self-exclusion (alice not in results)
    //    - limit=1 returns only top result
    //    - non-existent userVertexId → emptyList
    // 5. recommendFollows:
    //    - alice→dave(1)/eve(1) with tie-break ordering
    //    - user with no follows → emptyList
    //    - self-exclusion
    //    - limit=1 returns only top result
    //    - non-existent userVertexId → emptyList
}
```

각 테스트는 `assertFailsWith<IllegalArgumentException>` 사용 (validation). 추천 결과는 `shouldBeEqualTo` 사용.

**Acceptance**: TinkerGraph 기준 모든 테스트 통과.

---

### T9 — TinkerGraphRecommendationTest.kt
**complexity: low**
**File**: `src/test/kotlin/.../recommendation/TinkerGraphRecommendationTest.kt`

```kotlin
class TinkerGraphRecommendationTest : AbstractRecommendationTest() {
    override val graphName = "recommendation"
    override val ops = TinkerGraphOperations()
    // service는 AbstractRecommendationTest.service (by lazy) 상속 — 재정의 불필요
}
```

**Acceptance**: `./gradlew :graph-recommendation:test` 성공.

---

### T10 — Neo4jRecommendationTest.kt
**complexity: medium**
**File**: `src/test/kotlin/.../recommendation/Neo4jRecommendationTest.kt`

```kotlin
@Tag("integration")
class Neo4jRecommendationTest : AbstractRecommendationTest() {
    companion object : KLogging() {
        val neo4j = Neo4jServer.Launcher.neo4j
        val driver by lazy { GraphDatabase.driver(neo4j.boltUrl, neo4j.authToken) }
        val graphOps by lazy { Neo4jGraphOperations(driver) }
    }
    override val graphName = "neo4j_recommendation"
    override val ops get() = graphOps
    // service는 AbstractRecommendationTest.service (by lazy) 상속 — 재정의 불필요

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
    }
}
```

**Acceptance**: `./gradlew :graph-recommendation:integrationTest` 성공 (Neo4j).

---

### T11 — MemgraphRecommendationTest.kt
**complexity: medium**
**File**: `src/test/kotlin/.../recommendation/MemgraphRecommendationTest.kt`

```kotlin
@Tag("integration")
class MemgraphRecommendationTest : AbstractRecommendationTest() {
    companion object : KLogging() {
        val memgraph = MemgraphServer.Launcher.memgraph
        val driver by lazy {
            GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())  // AuthTokens.none() 필수
        }
        val graphOps by lazy { MemgraphGraphOperations(driver) }
    }
    override val graphName = "memgraph_recommendation"
    override val ops get() = graphOps
    // service는 AbstractRecommendationTest.service (by lazy) 상속 — 재정의 불필요

    @AfterAll
    fun tearDown() {
        runCatching { driver.close() }
    }
}
```

**Acceptance**: `./gradlew :graph-recommendation:integrationTest` 성공 (Memgraph).

---

### T12 — AbstractRecommendationSuspendTest.kt
**complexity: high**
**File**: `src/test/kotlin/.../recommendation/AbstractRecommendationSuspendTest.kt`

T8과 동일한 테스트 구조, 모든 `@Test`는 `runTest { }` 사용.

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRecommendationSuspendTest {
    protected abstract val graphName: String
    protected abstract val ops: GraphSuspendOperations

    /** Concrete classes may override; default is lazy-initialized from [ops] and [graphName]. */
    protected open val service: RecommendationSuspendService by lazy {
        RecommendationSuspendService(ops, graphName)
    }

    protected lateinit var seed: RecommendationSeed

    @BeforeEach
    fun cleanGraph() = runTest {
        ops.dropGraph(graphName)
        service.initialize()
        seed = seedRecommendation(service)
    }

    // Test groups — 동일 케이스 수 as T8:
    // 1. Vertex mutators: suspend addUser, addProduct (create + idempotent)
    // 2. Input validation: blank fields, rating > 5, limit = 0, limit = 101, self-follow
    // 3. Edge mutators: suspend purchase, follow
    // 4. recommendProducts (suspend):
    //    - alice→headphones(3)/keyboard(1)/mouse(1) with correct ordering
    //    - user with no purchases → emptyList
    //    - self-exclusion (alice not in results)
    //    - limit=1 returns only top result
    //    - non-existent userVertexId → emptyList
    // 5. recommendFollows (suspend):
    //    - alice→dave(1)/eve(1) with tie-break ordering
    //    - user with no follows → emptyList
    //    - self-exclusion
    //    - limit=1 returns only top result
    //    - non-existent userVertexId → emptyList

    @Test
    fun `recommendProducts returns correct results for alice`() = runTest {
        val results = service.recommendProducts(seed.alice.id)
        // assert headphones=3 is first
    }
    // ... (T8와 동일한 케이스 수 — 위 test group 목록 기준)
}
```

**Acceptance**: TinkerGraph 기준 모든 suspend 테스트 통과 (`runTest { }`). 케이스 수 `AbstractRecommendationTest`와 동일 (group 1-5 각 항목 모두 포함).

---

### T13 — TinkerGraphRecommendationSuspendTest.kt
**complexity: low**
**File**: `src/test/kotlin/.../recommendation/TinkerGraphRecommendationSuspendTest.kt`

```kotlin
class TinkerGraphRecommendationSuspendTest : AbstractRecommendationSuspendTest() {
    override val graphName = "recommendation_suspend"
    override val ops = TinkerGraphSuspendOperations()
    // service는 AbstractRecommendationSuspendTest.service (by lazy) 상속
}
```

**Acceptance**: `./gradlew :graph-recommendation:test` 성공 (TinkerGraph suspend 포함).

---

### T14 — Neo4jRecommendationSuspendTest.kt
**complexity: medium**
**File**: `src/test/kotlin/.../recommendation/Neo4jRecommendationSuspendTest.kt`

```kotlin
@Tag("integration")
class Neo4jRecommendationSuspendTest : AbstractRecommendationSuspendTest() {
    companion object : KLogging() {
        val neo4j = Neo4jServer.Launcher.neo4j
        val driver by lazy { GraphDatabase.driver(neo4j.boltUrl, neo4j.authToken) }
        val graphOps by lazy { Neo4jGraphSuspendOperations(driver) }
    }
    override val graphName = "neo4j_recommendation_suspend"
    override val ops get() = graphOps
    // service는 AbstractRecommendationSuspendTest.service (by lazy) 상속

    @AfterAll
    fun tearDown() { runCatching { driver.close() } }
}
```

**Acceptance**: `./gradlew :graph-recommendation:integrationTest` 성공 (Neo4j suspend).

---

### T15 — MemgraphRecommendationSuspendTest.kt
**complexity: medium**
**File**: `src/test/kotlin/.../recommendation/MemgraphRecommendationSuspendTest.kt`

```kotlin
@Tag("integration")
class MemgraphRecommendationSuspendTest : AbstractRecommendationSuspendTest() {
    companion object : KLogging() {
        val memgraph = MemgraphServer.Launcher.memgraph
        val driver by lazy {
            GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())
        }
        val graphOps by lazy { MemgraphGraphSuspendOperations(driver) }
    }
    override val graphName = "memgraph_recommendation_suspend"
    override val ops get() = graphOps
    // service는 AbstractRecommendationSuspendTest.service (by lazy) 상속

    @AfterAll
    fun tearDown() { runCatching { driver.close() } }
}
```

**Acceptance**: `./gradlew :graph-recommendation:integrationTest` 성공 (Memgraph suspend).

---

### T16 — README.md + README.ko.md
**complexity: medium**
**Files**: `graph/recommendation/README.md`, `graph/recommendation/README.ko.md`

구조:
1. Architecture diagram — **SVG+PNG** (`docs/images/readme-diagrams/recommendation-architecture.{svg,png}`); `bluetape4k-diagram` skill 호출 후 생성; ASCII fence 최종 README 사용 금지
2. Domain model (User/Product/PURCHASED/FOLLOWS)
3. Algorithm overview (Collaborative Filtering + FOAF)
4. Seed topology + expected results
5. Service API example
6. Backend support table (TinkerGraph/Neo4j/Memgraph)
7. Test commands

English README + Korean README 동시 작성.

---

### T17 — CLAUDE.md 모듈 테이블 업데이트
**complexity: low**
**File**: `CLAUDE.md` (worktree 루트)

현재 CLAUDE.md 모듈 테이블에 `graph/` 도메인 행이 없음. `graph/recommendation` 신규 서브모듈 추가에 맞춰 업데이트.

```
| `graph/` | 그래프 DB 예제: social-network, abuser-detection, knowledge-graph, recommendation |
```

(기존 `social-network`, `abuser-detection`, `knowledge-graph` 포함 통합 행 또는 `recommendation` 서브항목 추가.)

**Acceptance**: CLAUDE.md 모듈 테이블에 `graph/` 또는 `graph/recommendation` 참조 존재.

---

## 복잡도 요약

| Task | Subject | Complexity |
|------|---------|-----------|
| T1 | build.gradle.kts | low |
| T2 | RecommendationSchema.kt + Constants | low |
| T3 | Model classes | low |
| T4 | RecommendationService.kt | **high** |
| T5 | RecommendationSuspendService.kt | **high** |
| T6 | RecommendationSeed.kt | medium |
| T7 | Test resources | low |
| T8 | AbstractRecommendationTest.kt | **high** |
| T9 | TinkerGraphRecommendationTest.kt | low |
| T10 | Neo4jRecommendationTest.kt | medium |
| T11 | MemgraphRecommendationTest.kt | medium |
| T12 | AbstractRecommendationSuspendTest.kt | **high** |
| T13 | TinkerGraphRecommendationSuspendTest.kt | low |
| T14 | Neo4jRecommendationSuspendTest.kt | medium |
| T15 | MemgraphRecommendationSuspendTest.kt | medium |
| T16 | README.md + README.ko.md | medium |
| T17 | CLAUDE.md 모듈 테이블 업데이트 | low |

## Build Order

병렬 실행 가능 그룹:
- **Group 1** (독립): T1, T2, T3, T7
- **Group 2** (T2, T3 이후): T4, T6 병렬; T5는 **T4 커밋 이후** 시작 (T5는 T4의 diff 기준 구현)
- **Group 3a** (T4+T6 이후): T8
- **Group 3b** (T5+T6 이후): T12 (T5 의존 — T4가 아님)
- **Group 4** (T8 이후): T9, T10, T11
- **Group 5** (T12 이후): T13, T14, T15
- **Group 6** (모두 완료 후): T16, T17

> **T12 의존성 주의**: T12(`AbstractRecommendationSuspendTest`)는 T5(`RecommendationSuspendService`)에 의존. T4(blocking) 완료 전 T12 시작 시 compile 실패.

## DoD

- [ ] `./gradlew :graph-recommendation:test` — TinkerGraph 통과
- [ ] `./gradlew :graph-recommendation:integrationTest` — Neo4j + Memgraph 통과
- [ ] blocking/suspend 각 3개 backend concrete 클래스 존재
- [ ] 입력 검증 + self-follow + non-existent vertex 테스트 포함 (blocking + suspend 양쪽)
- [ ] **non-existent vertex → emptyList() 계약 테스트 포함** (blocking AbstractRecommendationTest + suspend AbstractRecommendationSuspendTest 양쪽)
- [ ] **AbstractRecommendationSuspendTest 테스트 케이스 수 = AbstractRecommendationTest 테스트 케이스 수** (group 1-5 동일 항목 포함)
- [ ] English KDoc on all public APIs
- [ ] CLAUDE.md 모듈 테이블에 `graph/` 행 추가 또는 `graph/recommendation` 주석 추가 (T17)

## Appendix: Step 3-R 리뷰 이력

| Round | Perspective | P0 | P1 | P2 | 적용 내용 | commit |
|-------|------------|----|----|----|---------| -------|
| Round 1 | Delivery | 0 | 6 | 0 | D1(T17), D2(pre-gate), D3(DoD), D4(KDoc acceptance), D5(SVG+PNG), D6(T1 acceptance) | TBD |
| Round 1 | Test Engineer | 0 | 2 | 1 | TE1(T12-T15 acceptance), TE2(DoD parity), TE3(T12 test list) | TBD |
| Round 1 | Implementer | 0 | 5 | 4 | I1(seed keys), I2(protected seed), I3(T12 seed), I4(Group 3b), I5(service by lazy) | TBD |
| Round 1 | Architect | 0 | 2 | 1 | A1(SVG+PNG=D5), A2(MAX_RECOMMENDATION_LIMIT 상수) | TBD |
| Round 1 | **합계** | **0** | **14** | **6** | 14 HIGH 모두 반영, plan 수정 | TBD |

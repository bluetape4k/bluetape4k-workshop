# graph/recommendation 모듈 설계 명세
_Date: 2026-05-25 | Issue #13_

## 1. 목표

bluetape4k-graph 라이브러리를 활용한 그래프 기반 추천 시스템 예제 모듈.

- **상품 추천**: 공통 구매자 기반 협업 필터링 (User–Product 이분 그래프 공통 이웃 탐색)
- **소셜 팔로우 추천**: 2홉 FOAF 기반 팔로우 추천 (FOLLOWS 단방향 그래프)

기존 패턴: `graph/social-network` → `graph/abuser-detection` → `graph/knowledge-graph` 와 동일한 구조.

---

## 2. 도메인 모델

### Vertex Types

| Object | Label | Properties | 설명 |
|--------|-------|-----------|------|
| `UserLabel` | `"User"` | `userId: String`, `name: String` | 구매자 / 팔로워 |
| `ProductLabel` | `"Product"` | `productId: String`, `name: String`, `category: String` | 구매 가능 상품 |

### Edge Types

| Object | Label | From → To | Properties | 설명 |
|--------|-------|----------|-----------|------|
| `PurchasedLabel` | `"PURCHASED"` | User → Product | `rating: String` (0-5, "0"=미평가), `purchasedAt: String` (ISO-8601, 빈 문자열 허용) | 구매 관계 (단방향) |
| `FollowsLabel` | `"FOLLOWS"` | User → User | (없음) | 팔로우 관계 (단방향) |

**결정 사항:**
- `Category` vertex 없음 — `category`는 Product 속성으로 표현 (YAGNI)
- `rating`: `Int 0..5` → 문자열 인코딩 저장. **`0`은 미평가 sentinel** — `rating > 0`인 경우만 edge property에 저장 (KnowsLabel.since 빈 값 스킵 패턴 동일)
- `purchasedAt`: ISO-8601 날짜 문자열 (예: `"2024-01-15"`), 빈 문자열 허용 (미입력)
- `PURCHASED`는 단방향 (User → Product)
- `FOLLOWS`는 단방향 (User → User), **자기 자신 팔로우 금지** (서비스 계층에서 검증)

---

## 3. 서비스 API

### 3-A. RecommendationService (Blocking) — 계약

#### 계약 명세 (H-7, H-8, H-9 fix)

```
## Behavior / Contract

initialize() MUST be called once before any other method.
It is idempotent — safe to call multiple times.
If the graph already exists, it is a no-op.

Vertex mutators (addUser, addProduct) implement find-or-create by domain key:
  - If a vertex with the given domain key already exists, return it.
  - Otherwise, create and return a new vertex.
  - The returned GraphVertex.id is the GraphElementId to pass to all subsequent methods.

To resolve a GraphElementId from a domain key after the fact (no object retained),
re-call addUser(userId, name) or addProduct(productId, name, category) — this is
the intended and documented usage pattern. There is no separate findUser/findProduct method.

  CORRECT:
    val alice = service.addUser("alice", "Alice")
    val laptop = service.addProduct("laptop", "Laptop Pro", "electronics")
    service.purchase(alice.id, laptop.id, rating = 5)
    service.recommendProducts(alice.id)

  WRONG (compiles if GraphElementId is a typealias for String, but semantically incorrect):
    service.recommendProducts(GraphElementId("alice"))   // domain key ≠ vertex id

Edge mutators (purchase, follow) require GraphElementId values returned by addUser/addProduct.
They do NOT accept domain String keys. Passing a domain key where GraphElementId is expected
may compile (if GraphElementId is a String typealias) but will produce incorrect results.
```

#### API 시그니처

```kotlin
class RecommendationService(
    private val ops: GraphOperations,
    private val graphName: String,
) {
    companion object : KLogging() {
        const val MAX_RECOMMENDATION_LIMIT: Int = 100
    }

    /** Idempotent. Must be called before any other method. */
    fun initialize()

    // Vertex mutators (find-or-create by domain key)
    // Returns the GraphVertex whose .id must be used in subsequent calls.
    fun addUser(userId: String, name: String): GraphVertex
    fun addProduct(productId: String, name: String, category: String = ""): GraphVertex

    // Edge mutators
    // userVertexId / productVertexId / followerVertexId / followeeVertexId:
    //   MUST be GraphVertex.id values returned by addUser / addProduct, NOT domain String keys.
    fun purchase(
        userVertexId: GraphElementId,
        productVertexId: GraphElementId,
        rating: Int = 0,            // 0 = not rated; stored only when > 0
        purchasedAt: String = "",   // ISO-8601 date; empty = not set
    ): GraphEdge

    fun follow(
        followerVertexId: GraphElementId,
        followeeVertexId: GraphElementId,   // MUST differ from followerVertexId
    ): GraphEdge

    // Recommendation algorithms
    // Behavior when userVertexId does not exist: returns emptyList()
    // Behavior when userVertexId refers to a non-User vertex: undefined; treat as emptyList()
    fun recommendProducts(
        userVertexId: GraphElementId,
        limit: Int = 10,
    ): List<ProductRecommendation>

    fun recommendFollows(
        userVertexId: GraphElementId,
        limit: Int = 10,
    ): List<FollowRecommendation>
}
```

**입력 검증:**
- `userId`, `name`, `productId`: `requireNotBlank()`
- `rating`: `requireInRange(0, 5, "rating")` (0 = 미평가 sentinel)
- `limit`: `requireInRange(1, MAX_RECOMMENDATION_LIMIT, "limit")`
- `follow()`: `require(followerVertexId != followeeVertexId) { "followerVertexId must differ from followeeVertexId" }`
- `purchasedAt`: 포맷 검증 없음 (백엔드 이식성) — KDoc에 ISO-8601 명시

**Edge 중복 계약**: `purchase()`/`follow()`는 중복 edge를 허용 (멱등성 없음). 호출자가 중복 방지 책임. 추천 알고리즘은 `distinct vertex` 기준으로 점수 계산하므로 중복 edge가 score에 영향 없음.

### 3-B. RecommendationSuspendService (Coroutine)

`GraphSuspendOperations` 기반 (H-OPS 관련 Codex 지적: blocking `GraphOperations` wrapping 금지).

```kotlin
class RecommendationSuspendService(
    private val ops: GraphSuspendOperations,   // GraphSuspendOperations — NOT GraphOperations
    private val graphName: String,
) {
    companion object : KLoggingChannel() {
        const val MAX_RECOMMENDATION_LIMIT: Int = 100
    }

    suspend fun initialize()
    suspend fun addUser(userId: String, name: String): GraphVertex
    suspend fun addProduct(productId: String, name: String, category: String = ""): GraphVertex
    suspend fun purchase(userVertexId: GraphElementId, productVertexId: GraphElementId,
                         rating: Int = 0, purchasedAt: String = ""): GraphEdge
    suspend fun follow(followerVertexId: GraphElementId, followeeVertexId: GraphElementId): GraphEdge

    // Returns List<T> (not Flow) — full materialization required for scoring/sorting
    suspend fun recommendProducts(userVertexId: GraphElementId, limit: Int = 10): List<ProductRecommendation>
    suspend fun recommendFollows(userVertexId: GraphElementId, limit: Int = 10): List<FollowRecommendation>
}
```

같은 입력 검증 및 계약 적용.

---

## 4. 모델 클래스

```kotlin
data class ProductRecommendation(
    val product: GraphVertex,
    val score: Int,                    // distinct 공통 구매자 수
    val sharedBuyers: List<GraphVertex>,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L
    }
}

data class FollowRecommendation(
    val person: GraphVertex,
    /**
     * Count of people that the seed user already follows who also follow [person].
     * These are the intermediaries through whom [person] was discovered as a candidate.
     * (Not "mutual follows" in the common sense of people both parties follow.)
     */
    val mutualFollowCount: Int,
    /** People the seed user follows who also follow [person]. */
    val mutualFollows: List<GraphVertex>,
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID: Long = 1L
    }
}
```

---

## 5. 알고리즘 설계

### 5-A. 상품 추천 (Collaborative Filtering)

**주의**: N+1 순회 — KDoc에 경고 명시 (social-network 동일 패턴).

```
recommendProducts(userVertexId, limit):
  1. myProducts = neighbors(userVertexId, PURCHASED, OUTGOING, depth=1)
     → List<GraphVertex> (product vertices)
  2. myProductIds = myProducts.map { it.id }.toSet()
  3. candidateMap = Map<GraphElementId, Pair<GraphVertex, MutableSet<GraphVertex>>>
     // key: candidate product vertex id
     // value: (candidate product vertex, set of co-buyer vertices — distinct)
  4. for each product in myProducts:
       coBuyers = neighbors(product.id, PURCHASED, INCOMING, 1)
                  .filter { it.id != userVertexId }
       for each coBuyer in coBuyers:
         theirProducts = neighbors(coBuyer.id, PURCHASED, OUTGOING, 1)
                         .filter { it.id !in myProductIds }
         for each candidate in theirProducts:
           candidateMap.getOrPut(candidate.id) { candidate to mutableSetOf() }
                       .second += coBuyer   // coBuyer is GraphVertex — add to set
  5. Build ProductRecommendation per entry in candidateMap:
       product = entry.value.first              // GraphVertex
       sharedBuyers = entry.value.second.toList() // List<GraphVertex> (distinct)
       score = sharedBuyers.size
  6. Sort: score DESC, then product.properties["productId"] ASC (tie-breaker)
  7. Take(limit) → List<ProductRecommendation>
```

### 5-B. 팔로우 추천 (FOAF on FOLLOWS)

**주의**: N+1 순회 — KDoc에 경고 명시 (5-A와 동일 패턴).

```
recommendFollows(userVertexId, limit):
  1. myFollows = neighbors(userVertexId, FOLLOWS, OUTGOING, 1)
     → List<GraphVertex> (people seed already follows)
  2. myFollowIds = myFollows.map { it.id }.toSet()
  3. depth2 = neighbors(userVertexId, FOLLOWS, OUTGOING, 2)
     → deduplicate, filter { it.id != userVertexId && it.id !in myFollowIds }
     → candidates: List<GraphVertex>
  4. for each candidate in candidates:
       whoFollowsCandidate = neighbors(candidate.id, FOLLOWS, INCOMING, 1)
       intermediaries = whoFollowsCandidate.filter { it.id in myFollowIds }
         → these are people seed follows who also follow candidate
  5. Build FollowRecommendation per candidate:
       mutualFollows = intermediaries (List<GraphVertex>)
       mutualFollowCount = intermediaries.size
  6. Filter: mutualFollowCount > 0
  7. Sort: mutualFollowCount DESC, then candidate.properties["userId"] ASC (tie-breaker)
  8. Take(limit) → List<FollowRecommendation>
```

**Semantic note**: `mutualFollows` = "people the seed already follows, who also follow the candidate" (intermediaries). This is not the same as "people both seed and candidate follow."

---

## 6. Seed 데이터 (테스트용)

### 사용자 및 상품

```
Users:    alice, bob, carol, dave, eve, frank
Products: laptop, phone, tablet, headphones, keyboard, mouse
```

### 구매 관계 (User → Product)

| User  | 구매 상품                            |
|-------|--------------------------------------|
| alice | laptop, phone, tablet                |
| bob   | laptop, phone, headphones            |
| carol | laptop, tablet, keyboard             |
| dave  | phone, headphones, mouse             |
| eve   | laptop, phone, tablet, headphones    |
| frank | keyboard, mouse                      |

### 팔로우 관계 (User → User)

| 팔로워 | 팔로이                |
|--------|----------------------|
| alice  | bob, carol           |
| bob    | alice, dave          |
| carol  | alice, eve           |
| dave   | bob, frank           |
| eve    | alice, bob, carol    |
| frank  | dave                 |

### 예상 추천 결과 (alice 기준)

**상품 추천:**
- headphones: score=3 (bob, dave, eve — distinct 공통 구매자)
- keyboard: score=1 (carol)
- mouse: score=1 (dave)
- 정렬: headphones(3) > keyboard(1) = mouse(1), tie-break productId ASC → keyboard before mouse

**팔로우 추천:**
- dave: mutualFollowCount=1 (alice→bob→dave; bob이 intermediary)
- eve: mutualFollowCount=1 (alice→carol→eve; carol이 intermediary)
- 정렬: tie-break userId ASC → dave before eve

---

## 7. 파일 구조

```
graph/recommendation/
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/graph/recommendation/
    schema/
      RecommendationSchema.kt       # VertexLabel + EdgeLabel
    model/
      ProductRecommendation.kt      # 상품 추천 결과
      FollowRecommendation.kt       # 팔로우 추천 결과
    service/
      RecommendationService.kt      # blocking (GraphOperations)
      RecommendationSuspendService.kt # coroutine (GraphSuspendOperations)
  src/test/kotlin/io/bluetape4k/workshop/graph/recommendation/
    seed/
      RecommendationSeed.kt
    AbstractRecommendationTest.kt
    AbstractRecommendationSuspendTest.kt
    TinkerGraphRecommendationTest.kt            # no Docker; graphName = "recommendation"
    Neo4jRecommendationTest.kt                  # @Tag("integration"); graphName = "neo4j_recommendation"
    MemgraphRecommendationTest.kt               # @Tag("integration"); graphName = "memgraph_recommendation"
    TinkerGraphRecommendationSuspendTest.kt     # no Docker; graphName = "recommendation_suspend"
    Neo4jRecommendationSuspendTest.kt           # @Tag("integration"); graphName = "neo4j_recommendation_suspend"
    MemgraphRecommendationSuspendTest.kt        # @Tag("integration"); graphName = "memgraph_recommendation_suspend"
  src/test/resources/
    junit-platform.properties
    logback-test.xml
  README.md
  README.ko.md
```

---

## 8. Gradle 설정

```kotlin
// build.gradle.kts
plugins { alias(libs.plugins.kotlin.jvm) }

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.register<Test>("integrationTest") {
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)."
    group = "verification"
    useJUnitPlatform { includeTags("integration") }
    jvmArgs = tasks.test.get().jvmArgs
}

dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    compileOnly(libs.bluetape4k.graph.neo4j)
    compileOnly(libs.bluetape4k.graph.memgraph)

    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.coroutines)
    implementation(libs.kotlinx.coroutines.core.lib)
    testImplementation(libs.kotlinx.coroutines.test.lib)

    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.neo4j)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.mockk)
}
```

---

## 9. 테스트 구조

### graphName 규칙 (H-5 fix)

| 테스트 클래스 | graphName |
|--------------|-----------|
| TinkerGraphRecommendationTest | `"recommendation"` |
| Neo4jRecommendationTest | `"neo4j_recommendation"` |
| MemgraphRecommendationTest | `"memgraph_recommendation"` |
| TinkerGraphRecommendationSuspendTest | `"recommendation_suspend"` |
| Neo4jRecommendationSuspendTest | `"neo4j_recommendation_suspend"` |
| MemgraphRecommendationSuspendTest | `"memgraph_recommendation_suspend"` |

### Memgraph 드라이버 패턴 (H-4, H-6 fix)

```kotlin
// by lazy 패턴 사용 (lateinit var + @BeforeAll 금지)
// AuthTokens.none() 필수 — Memgraph는 인증 없는 드라이버 연결 요구
companion object {
    val memgraph = MemgraphServer.Launcher.memgraph
    val driver by lazy {
        GraphDatabase.driver(memgraph.boltUrl, AuthTokens.none())
    }
    val ops: MemgraphGraphOperations by lazy {
        MemgraphGraphOperations(driver)
    }
}
@AfterAll
fun tearDown() {
    runCatching { driver.close() }
}
```

### @Tag 규칙 (H-5 보완)

Docker 기반 4개 클래스 모두 `@Tag("integration")` 필수:
- `Neo4jRecommendationTest`
- `MemgraphRecommendationTest`
- `Neo4jRecommendationSuspendTest`
- `MemgraphRecommendationSuspendTest`

### AbstractRecommendationTest 케이스

| 그룹 | 테스트 |
|------|--------|
| Vertex mutators | addUser 생성, idempotent, addProduct 생성 |
| Edge mutators | purchase 생성, follow 생성 |
| Input validation | blank userId, blank name, rating 범위 초과 (6), limit 범위 초과 (0, 101), self-follow 금지 |
| recommendProducts | 기본 추천 (headphones=3, ordering), 빈 구매 이력 → emptyList, self-exclusion, limit 적용, non-existent userVertexId → emptyList |
| recommendFollows | 기본 추천 (dave/eve mutual), 빈 팔로우 → emptyList, self-exclusion, limit 적용, non-existent userVertexId → emptyList |

### AbstractRecommendationSuspendTest

Blocking 테스트를 `runTest { }` 로 미러링. 동일 케이스 수.

---

## 10. DoD 완료 기준

- [ ] `RecommendationService` / `RecommendationSuspendService` 구현
- [ ] 3개 백엔드 기본 테스트 통과: `./gradlew :graph-recommendation:test` (TinkerGraph)
- [ ] 3개 백엔드 통합 테스트 통과: `./gradlew :graph-recommendation:integrationTest` (Neo4j, Memgraph)
- [ ] Suspend 변형 테스트 포함 (3 concrete 클래스)
- [ ] 입력 검증 테스트 포함 (self-follow, rating/limit 범위)
- [ ] README.md + README.ko.md 작성
- [ ] English KDoc on all public APIs
- [ ] non-existent vertex → emptyList() 계약 테스트 포함

---

## Appendix: Step 2-R 리뷰 이력

| Round | Reviewer | P0/P1 수 | 적용 내용 | commit |
|-------|---------|---------|----------|--------|
| Round 1 | Developer | 3 HIGH | H-1 rating fix, H-2 self-loop guard, H-3 algorithm pseudocode | - |
| Round 1 | Security | 0 HIGH | - | - |
| Round 1 | Ops/SRE | 3 HIGH | H-4 AuthTokens.none, H-5 graphName, H-6 by lazy | - |
| Round 1 | User/caller | 4 HIGH | H-7 dual-ID contract, H-8 findUser doc, H-9 initialize() | - |
| Round 1 | Codex | 0 HIGH | MEDIUM: RecommendationSuspendService uses GraphSuspendOperations | - |
| Round 1 Critic | rate-limited | N/A | Manual integration applied | - |
| **Round 1 total** | | **10 HIGH → 0 after fix** | All applied to spec | TBD |

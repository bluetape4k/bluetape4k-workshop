# Social Network Graph 모듈 설계 — graph/social-network

**Date**: 2026-05-25  
**Status**: Draft  
**Author**: AI-assisted (Claude)  
**Base Module**: `graph/abuser-detection` (canonical pattern)  
**Reference**: `bluetape4k-graph/examples/linkedin-graph-examples`

---

## 1. 개요

LinkedIn 스타일 소셜 네트워크 그래프를 `bluetape4k-graph` API로 구현하는 워크샵 예제 모듈.
사람(Person), 회사(Company) 정점과 KNOWS, WORKS_AT, FOLLOWS 관계를 모델링하고,
인맥 탐색, FOAF(Friend-of-a-Friend) 추천, 동료 검색 등 실용적인 그래프 알고리즘을 시연한다.

`abuser-detection` 모듈의 파일 레이아웃, find-or-create 패턴, dual service(blocking/suspend),
abstract test hierarchy를 그대로 따르되,
`linkedin-graph-examples`의 도메인 스키마(PersonLabel, CompanyLabel, KnowsLabel 등)를 차용한다.

---

## 2. 목표

- **그래프 기반 소셜 네트워크의 장점 시연**: 관계 기반 쿼리(N차 인맥, 경로 탐색, 추천)가
  SQL JOIN 대비 직관적이고 성능적으로 유리함을 보여준다.
- **bluetape4k-graph API 활용**: `neighbors()`, `shortestPath()`, `allPaths()`,
  `connectedComponents()`, `degreeCentrality()` 등 핵심 API를 실전 시나리오에 적용한다.
- **FOAF 추천 알고리즘**: 공통 인맥(mutual connections) 기반 인맥 추천을 구현한다.
- **3-backend 테스트**: TinkerGraph(단위) + Neo4j, Memgraph(통합) 동일 테스트 스위트 실행.
- **blocking + suspend 이중 서비스**: 동일 도메인에 대해 `GraphOperations` /
  `GraphSuspendOperations` 양쪽 구현을 제공한다.

---

## 3. 범위

### 3.1 포함 (In Scope)

- Schema 정의: `PersonLabel`, `CompanyLabel`, `KnowsLabel`, `WorksAtLabel`, `FollowsLabel`
- 도메인 모델: `ConnectionRecommendation` data class
- Blocking 서비스: `SocialNetworkService`
- Suspend 서비스: `SocialNetworkSuspendService`
- 테스트 시드 유틸: `SocialNetworkSeed.kt`
- Abstract 테스트 베이스 x 2 (blocking, suspend) + Concrete x 6 (3 backend x 2 style)
- `build.gradle.kts`, `junit-platform.properties`, `logback-test.xml`

### 3.2 제외 (Out of Scope)

- REST API endpoint (Spring Boot 의존 없음)
- AGE / FalkorDB 통합 테스트 (향후 확장 가능, 이 모듈에서는 미구현)
- Skill / HasSkill / Endorses 정점/간선 (linkedin-graph-examples에는 있으나 핵심
  시나리오에 불필요하므로 제외)
- 가중치 경로 탐색 (Memgraph `weightProperty` 미지원 이슈 회피)

---

## 4. 도메인 모델

### 4.1 Vertex Labels

| Object | Label | Key Properties | 비고 |
|--------|-------|----------------|------|
| `PersonLabel` | `"Person"` | `personId` (unique key), `name`, `title`, `location` | find-or-create by `personId` |
| `CompanyLabel` | `"Company"` | `companyId` (unique key), `name`, `industry`, `location` | find-or-create by `companyId` |

**설계 결정**: `PersonLabel.personId`를 도메인 키로 사용한다. `name`은 중복 가능하므로
멱등성 보장 키로 적합하지 않다. `linkedin-graph-examples`는 `name`을 키처럼 사용하지만,
이 모듈에서는 `abuser-detection`의 `userId` 패턴을 따라 별도의 `personId` 필드를 둔다.

### 4.2 Edge Labels

| Object | Label | From -> To | Key Properties | 방향성 |
|--------|-------|-----------|----------------|--------|
| `KnowsLabel` | `"KNOWS"` | Person -> Person | `since` (ISO date string, optional), `strength` (Int, 1–10) | **양방향** (A->B, B->A 두 edge 생성) |
| `WorksAtLabel` | `"WORKS_AT"` | Person -> Company | `role` (required), `startDate` (ISO date string, optional), `isCurrent` (Boolean) | 단방향 |
| `FollowsLabel` | `"FOLLOWS"` | Person -> Person | (없음) | 단방향 |

---

## 5. API 설계

### 5.1 SocialNetworkService (blocking)

```kotlin
class SocialNetworkService(
    private val ops: GraphOperations,
    private val graphName: String = "social_network",
) {
    companion object : KLogging() {
        /** Maximum allowed traversal depth for degree-based and path-based queries. */
        const val MAX_TRAVERSAL_DEPTH: Int = 6
    }

    // ── Lifecycle ──
    fun initialize()

    // ── Vertex mutators (find-or-create by domain key) ──
    fun addPerson(personId: String, name: String, title: String = "", location: String = ""): GraphVertex
    fun addCompany(companyId: String, name: String, industry: String = "", location: String = ""): GraphVertex

    // ── Edge mutators ──
    /**
     * Creates a bidirectional KNOWS relationship between two persons.
     *
     * **IMPORTANT**: This method creates TWO directed edges (personVertexId1→personVertexId2
     * AND personVertexId2→personVertexId1). Do NOT call connect(B,A) after connect(A,B) —
     * both directions are already created. Duplicate calls produce 4 edges (double-counting).
     *
     * Both edges carry identical `since` and `strength` property values.
     *
     * **Non-atomic**: if the second edge creation fails, the graph is left in an asymmetric
     * state (one-directional KNOWS). Workshop scope accepts this risk; re-seeding is idempotent
     * at vertex level.
     */
    fun connect(personVertexId1: GraphElementId, personVertexId2: GraphElementId, since: String = "", strength: Int = 5)
    fun follow(followerVertexId: GraphElementId, targetVertexId: GraphElementId)

    /**
     * Creates a WORKS_AT edge from a person to a company.
     * Non-idempotent: duplicate calls create duplicate WORKS_AT edges.
     * `findColleagues` deduplicates by vertex ID, so duplicates are safe for queries.
     */
    fun addWorkExperience(personVertexId: GraphElementId, companyVertexId: GraphElementId, role: String, startDate: String = "", isCurrent: Boolean = false)

    // ── Query: 인맥 탐색 ──
    fun getDirectConnections(personVertexId: GraphElementId): List<GraphVertex>

    /**
     * Returns all persons reachable via KNOWS edges within [degree] hops.
     * @param degree must be in 1..[MAX_TRAVERSAL_DEPTH] (enforced via require())
     */
    fun getConnectionsWithinDegree(personVertexId: GraphElementId, degree: Int): List<GraphVertex>

    /**
     * Returns persons reachable at exactly [degree] hops (not closer).
     * @param degree must be in 1..[MAX_TRAVERSAL_DEPTH] (enforced via require())
     */
    fun getNthDegreeConnections(personVertexId: GraphElementId, degree: Int): List<GraphVertex>
    fun findConnectionPath(fromVertexId: GraphElementId, toVertexId: GraphElementId): GraphPath?

    /**
     * Returns all paths between two vertices up to [maxDepth] hops.
     * **WARNING**: Path enumeration is exponential. Keep maxDepth ≤ 6 to avoid combinatorial explosion.
     * @param maxDepth maximum path length, default 5, capped at MAX_TRAVERSAL_DEPTH
     */
    fun findAllConnectionPaths(fromVertexId: GraphElementId, toVertexId: GraphElementId, maxDepth: Int = 5): List<GraphPath>

    // ── Query: 공통 인맥 & 추천 ──
    fun findMutualConnections(personVertexId1: GraphElementId, personVertexId2: GraphElementId): List<GraphVertex>

    /**
     * Recommends connections using FOAF (Friend-of-a-Friend) algorithm.
     * Returns candidates sorted by mutual connection count descending.
     * Ties are broken by vertex ID ascending for deterministic ordering.
     * @see findMutualConnections for retrieving shared connections between two specific persons
     */
    fun recommendConnections(personVertexId: GraphElementId, limit: Int = 10): List<ConnectionRecommendation>

    // ── Query: 직장 관련 ──
    /** Returns current and past colleagues at the same company. Deduplicated by vertex ID. */
    fun findColleagues(personVertexId: GraphElementId): List<GraphVertex>
}
```

### 5.2 SocialNetworkSuspendService (suspend/Flow)

```kotlin
class SocialNetworkSuspendService(
    private val ops: GraphSuspendOperations,
    private val graphName: String = "social_network",
) {
    companion object : KLoggingChannel()

    // ── Lifecycle ──
    suspend fun initialize()

    // ── Vertex mutators (find-or-create by domain key) ──
    suspend fun addPerson(personId: String, name: String, title: String = "", location: String = ""): GraphVertex
    suspend fun addCompany(companyId: String, name: String, industry: String = "", location: String = ""): GraphVertex

    // ── Edge mutators ──
    /**
     * Creates a bidirectional KNOWS relationship. Identical contract to [SocialNetworkService.connect].
     * Both directed edges carry identical `since` and `strength` values.
     * Non-atomic: second edge creation failure leaves asymmetric state.
     */
    suspend fun connect(personVertexId1: GraphElementId, personVertexId2: GraphElementId, since: String = "", strength: Int = 5)
    suspend fun follow(followerVertexId: GraphElementId, targetVertexId: GraphElementId)

    /**
     * Non-idempotent: duplicate calls create duplicate WORKS_AT edges.
     * [findColleagues] deduplicates by vertex ID.
     */
    suspend fun addWorkExperience(personVertexId: GraphElementId, companyVertexId: GraphElementId, role: String, startDate: String = "", isCurrent: Boolean = false)

    // ── Query: 인맥 탐색 ──
    // Note: GraphSuspendOperations.neighbors() returns Flow<GraphVertex>;
    // all List-returning methods below collect the Flow via .toList() internally.
    suspend fun getDirectConnections(personVertexId: GraphElementId): List<GraphVertex>
    suspend fun getConnectionsWithinDegree(personVertexId: GraphElementId, degree: Int): List<GraphVertex>
    suspend fun getNthDegreeConnections(personVertexId: GraphElementId, degree: Int): List<GraphVertex>
    suspend fun findConnectionPath(fromVertexId: GraphElementId, toVertexId: GraphElementId): GraphPath?
    suspend fun findAllConnectionPaths(fromVertexId: GraphElementId, toVertexId: GraphElementId, maxDepth: Int = 5): List<GraphPath>

    // ── Query: 공통 인맥 & 추천 ──
    suspend fun findMutualConnections(personVertexId1: GraphElementId, personVertexId2: GraphElementId): List<GraphVertex>
    /** Ties broken by vertex ID ascending for deterministic ordering. */
    suspend fun recommendConnections(personVertexId: GraphElementId, limit: Int = 10): List<ConnectionRecommendation>

    // ── Query: 직장 관련 ──
    /** Returns current and past colleagues. Deduplicated by vertex ID. */
    suspend fun findColleagues(personVertexId: GraphElementId): List<GraphVertex>
}
```

### 5.3 ConnectionRecommendation

```kotlin
/**
 * FOAF recommendation result.
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

## 6. 알고리즘 설계

### 6.1 FOAF 추천 알고리즘

> **Set operation semantics**: 이 섹션의 모든 집합 연산(∩, -, union)은 **vertex ID 기준**으로 수행한다.

**목적**: 공통 인맥이 많은 2촌 인맥을 추천한다.

**알고리즘**:
```
1. directFriends = neighbors(seed, KNOWS, OUTGOING, depth=1)         // 1 round-trip
2. friendsOfFriends = neighbors(seed, KNOWS, OUTGOING, depth=2)      // 1 round-trip
3. foafCandidates = friendsOfFriends - directFriends - {seed}        // ID 기준 집합 차
   ⚠️ {seed} explicit 제거 필수:
      neighbors()는 seed 제외를 보장하지 않는다.
      양방향 KNOWS에서 depth=2 탐색 시 A→B→A 경로를 통해 seed가 반환될 수 있다.
4. 각 candidate에 대해:                                              // M round-trips (N+1 패턴)
   a. candidateFriends = neighbors(candidate, KNOWS, OUTGOING, depth=1)
   b. mutualConnections = directFriends ∩ candidateFriends (ID 기준 교집합)
   c. mutualCount = |mutualConnections|
5. mutualCount 기준 내림차순 정렬; 동점 시 personId (domain key) 오름차순 (결정적 정렬)
   ⚠️ 백엔드 내부 GraphElementId 사용 금지 — Neo4j/Memgraph/TinkerGraph 간 일관성 없음
6. 상위 limit개 반환
   (mutualCount=0 필터 없음 — depth=2 FOAF 후보는 정의상 최소 1개의 mutual connection을 가짐)
```

**핵심 결정**:
- Step 3: `{seed}` 명시적 제거는 필수. `neighbors()` 계약이 seed를 제외한다고 명시하지 않으며,
  양방향 KNOWS + depth=2 조합에서 seed vertex가 반환될 수 있다 (A→B→A traversal).
- Step 4b에서 실제 mutual vertex 목록을 수집해 `ConnectionRecommendation.mutualConnections`에
  저장한다. count만 반환하는 것보다 UI 표시에 유용하다.
- Step 5 동점 처리: vertex ID 오름차순을 secondary sort로 사용해 테스트 결과가 결정적이 되게 한다.
- **성능**: M+2 round-trip (M = FOAF 후보 수). 워크샵 규모에서는 허용 가능.
  프로덕션이라면 단일 Cypher/Gremlin 쿼리로 구현해야 한다 (Section 11 리스크 6 참조).
- **반환 타입**: blocking/suspend 모두 `List<ConnectionRecommendation>`을 반환한다.
  정렬(Step 5)이 전체 후보 수집 후에만 가능하므로 `Flow`는 의미적으로 부적합하다.

### 6.2 N차 연결 탐색

> **Set operation semantics**: 모든 집합 연산은 **vertex ID 기준**으로 수행한다.
> **`degree` validation**: `require(degree in 1..MAX_TRAVERSAL_DEPTH)` — MAX_TRAVERSAL_DEPTH = 6.

**`getConnectionsWithinDegree(personVertexId, degree)`**:
```
neighbors(personVertexId, KNOWS, OUTGOING, maxDepth=degree)
```
- `NeighborOptions.maxDepth`가 depth > 1을 지원함을 확인 완료 (모든 3개 백엔드).
- ⚠️ `neighbors()` 구현은 seed vertex 제외를 보장하지 않는다.
  depth=1에서는 seed가 반환되지 않지만, depth ≥ 2에서는 양방향 KNOWS 사이클(A→B→A)로
  seed가 반환될 가능성이 있다. **`- {seed}` 명시적 제거를 구현 시 적용해야 한다.**

**`getNthDegreeConnections(personVertexId, degree)`**:
```
allWithin = neighbors(personVertexId, KNOWS, OUTGOING, maxDepth=degree)   - {seed}
closer    = if (degree > 1) neighbors(personVertexId, KNOWS, OUTGOING, maxDepth=degree-1) - {seed}
            else emptyList()
result    = allWithin - closer  // ID 기준 집합 차 (정확히 N촌만)
```
- 정확히 N촌에 해당하는 사람만 추출.
- `degree=1`이면 `closer`는 빈 리스트이므로 `getDirectConnections`와 동일 결과.
- 양방향 KNOWS이므로 `Direction.OUTGOING`만으로 전체 인맥 탐색 가능.
- 이중 traversal (2× neighbors 호출): 워크샵 규모에서 허용 가능. 프로덕션은 BFS level-tracking 권장.

### 6.3 공통 인맥 (Mutual Connections)

```
friendsA = neighbors(personId1, KNOWS, OUTGOING, depth=1)
friendsB = neighbors(personId2, KNOWS, OUTGOING, depth=1)
mutuals  = friendsA ∩ friendsB   // ID 기준 교집합
```

### 6.4 동료 검색 (findColleagues)

```
companies = neighbors(personId, WORKS_AT, OUTGOING, depth=1)  // 재직 회사 목록
colleagues = flatMap(companies) { companyVertex ->
    neighbors(companyVertex.id, WORKS_AT, INCOMING, depth=1)
}
result = colleagues - {seed}  // 자기 자신 제외
```

**`isCurrent` 필터링**: `findColleagues`는 `isCurrent` 속성을 필터링하지 않으며
현재 및 과거 동료 모두를 반환한다. 이유:
- `neighbors()` API는 vertex properties 기반 필터를 지원하지 않으며, 반환된 edge의
  properties를 후처리로 필터링해야 하는데, `neighbors()`는 vertex만 반환하므로
  edge properties 접근이 불가하다.
- 워크샵 목적상 "같은 회사에 재직한 적 있는 사람" 전체를 반환하는 것이 적절하다.
- 테스트 시드에 `isCurrent=false`인 과거 동료를 포함해 이 동작을 검증한다.

---

## 7. 기술적 결정

### 7.1 KNOWS 양방향 처리

**결정**: `connect(A, B)` 호출 시 `A→B`, `B→A` 두 개의 directed edge를 생성한다.

**근거**:
- `GraphOperations`는 undirected edge를 지원하지 않는다.
- `Direction.OUTGOING`만으로 양방향 탐색이 가능해져 쿼리가 단순해진다.
- `linkedin-graph-examples`와 동일한 패턴 (검증 완료).

**비용**: 엣지 수 2배. 워크샵 규모(수십~수백 정점)에서는 무시 가능.

**`connect()` 멱등성**: 비멱등(non-idempotent). 중복 호출 시 중복 edge가 생성된다.
이는 `abuser-detection`의 edge mutator 계약과 일치한다.
KDoc에 양방향 경고 + 중복 호출 금지 명시 (Sections 5.1/5.2 참조).

**edge 속성 대칭성**: `connect(A, B, since="2024-01-01", strength=8)` 호출 시
`A→B`와 `B→A` 두 edge 모두 동일한 `since`와 `strength` 값을 갖는다.
구현은 두 번째 `createEdge` 호출에 첫 번째와 동일한 properties를 전달해야 한다.
테스트: "connect creates bidirectional KNOWS edges with identical properties"로 양방향 속성 대칭성 검증.

**부분 실패 (Partial Failure)**:
- `createEdge(A→B)` 성공 후 `createEdge(B→A)` 실패 시 그래프는 비대칭 상태 (A는 B를 알지만 B는 A를 모름).
- `GraphOperations`는 transaction wrapping을 보장하지 않으며, 백엔드별 트랜잭션 지원 여부도 다르다.
- **워크샵 범위**: 부분 실패 보상 전략 없이 Known Limitation으로 문서화한다.
  테스트 시드는 항상 `initialize()` 후 시드 데이터를 새로 생성하므로, 부분 실패는 다음 테스트 시작 시 
  `dropGraph()`로 초기화된다.
- KDoc 주석에 이 제약을 명시해야 한다.

### 7.2 find-or-create 멱등성

**결정**: `addPerson`, `addCompany` vertex mutator는 멱등(idempotent).

**패턴** (abuser-detection 동일):
```kotlin
fun addPerson(personId: String, ...): GraphVertex {
    personId.requireNotBlank("personId")
    return ops.findVerticesByLabel(PersonLabel.label, mapOf(PersonLabel.personId.name to personId))
        .firstOrNull()
        ?: ops.createVertex(PersonLabel.label, mapOf(...))
}
```

**검색 기준**: domain key (`personId`, `companyId`)만으로 find-or-create 일치 판정.
`name`, `title`, `location` 등 optional metadata는 find-or-create 일치 기준에 포함되지 않는다.
따라서 두 번째 `addPerson(sameId, differentName)` 호출은 기존 vertex를 반환하며 properties를 갱신하지 않는다.

**파라미터 validation 정책**:
- **domain key** (required, `requireNotBlank`): `personId`, `companyId`, `role`
- **optional metadata** (빈 문자열 허용, validation 없음): `name`, `title`, `location`, `industry`, `since`, `startDate`
- **numeric** (required in range): `strength: Int` — `require(strength in 1..10)`; `degree: Int` — `require(degree in 1..MAX_TRAVERSAL_DEPTH)`; `limit: Int` — `requirePositiveNumber()`
- ⚠️ `requireNotBlank()`을 optional metadata에 적용하면 빈 문자열 기본값과 충돌한다. domain key 전용으로 한정.

### 7.3 Memgraph 제약사항

- **가중치 경로 탐색**: Memgraph는 `PathOptions.weightProperty` 미지원.
  이 모듈에서는 가중치 경로를 사용하지 않으므로 영향 없음.
  `findConnectionPath`는 비가중치 BFS 최단 경로만 사용.
- **PageRank**: 이 모듈에서는 PageRank를 사용하지 않으므로 Memgraph 호환성 문제 없음.
- 모든 API가 비가중치 `neighbors()`, `shortestPath()`, `allPaths()`만 사용하므로
  **3개 백엔드 전체에서 동일 테스트 스위트 실행 가능**.

---

## 8. 테스트 전략

### 8.1 테스트 데이터 그래프 토폴로지

```
                    ┌─── [Company: Bluetape4k] ───┐
                    │         ▲                    │
               WORKS_AT       │              WORKS_AT
                    │     WORKS_AT                 │
                    │         │                    │
[Person: Alice] ─KNOWS─ [Person: Bob] ─KNOWS─ [Person: Carol]
       │                      │
       │                 KNOWS│
       │                      │
       KNOWS             [Person: Dave] ──KNOWS──[Person: Grace]
       │                      │
       ▼                 WORKS_AT        Carol──FOLLOWS──▶[Person: Eve]
[Person: Frank]               │
                              ▼
                    [Company: Acme]
```

**토폴로지 의도**:
- Alice-Bob: 1촌 (KNOWS 양방향)
- Bob-Carol: 1촌 (KNOWS 양방향)
- Alice-Carol: 2촌 (Alice → Bob → Carol), mutual = {Bob}
- Alice-Frank: 1촌 (KNOWS 양방향)
- Bob-Dave: 1촌 (KNOWS 양방향)
- Dave-Grace: 1촌 (KNOWS 양방향)
- Alice-Dave: 2촌 via Bob, mutual = {Bob}
- Alice-Grace: 3촌 (Alice→Bob→Dave→Grace)
- Carol → Eve: FOLLOWS (단방향, KNOWS 아님 — `getDirectConnections(Carol)` 결과에 Eve 미포함)
- Alice, Bob, Carol: Bluetape4k 재직 → 동료
- Dave: Acme 재직 → Alice/Bob/Carol과 비동료

**FOAF 추천 결과 (Alice 기준)**:
- 1촌 (직접 인맥): {Bob, Frank} — 추천 제외
- FOAF 후보 (depth=2): Carol (via Bob, mutual={Bob}), Dave (via Bob, mutual={Bob})
- Grace는 depth=3 (Alice→Bob→Dave→Grace) — FOAF 후보에 포함되지 않음 (depth=2 이내 아님)

⚠️ **FOAF 알고리즘의 "zero mutual" 필터는 사실상 dead code**:
depth=2 이내의 KNOWS 연결은 정의상 최소 1개의 공통 직접 인맥(경로 상의 중간 노드)을 가진다.
따라서 `mutualCount=0` 후보는 이 알고리즘에서 존재할 수 없다.
`mutualCount=0` 필터는 스펙에서 제거하고, 테스트도 삭제한다.

**동점 처리**: 동점 시 `personId` domain key 오름차순 (백엔드 내부 ID는 Neo4j/Memgraph/TinkerGraph 간 일관성이 없음).
Alice FOAF 추천 결과 = [Carol(1), Dave(1)] — 동점, personId 오름차순으로 정렬.
테스트 assertion: `recommendConnections(alice).map { it.person.name }` ⊇ {"Carol", "Dave"} (순서는 allowAnyOrder, 동점이므로)

### 8.2 TinkerGraph (단위 테스트)

- `./gradlew :graph-social-network:test` 로 실행
- Docker 불필요, CI 기본 테스트에 포함
- TinkerGraph in-memory 백엔드 사용

### 8.3 Neo4j / Memgraph (통합 테스트)

- `./gradlew :graph-social-network:integrationTest` 로 실행
- `@Tag("integration")` 태그로 기본 `:test`에서 제외
- Testcontainers 싱글턴: `Neo4jServer.Launcher.neo4j`, `MemgraphServer.Launcher.memgraph`
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`

### 8.4 패키지 및 테스트 구조

**패키지 루트**: `io.bluetape4k.workshop.graph.social`
(abuser-detection의 `io.bluetape4k.workshop.graph.abuser`와 병렬 구조)

**Graph name 정책 (⚠️ 필수)**: 각 concrete test class는 **고유한 `graphName`**을 사용해야 한다.
동일한 container에서 실행되는 여러 test class가 같은 graphName을 공유하면 상태가 충돌한다.

| Concrete class | graphName |
|---|---|
| SocialNetworkTinkerGraphTest | `"test_social_tinkergraph"` |
| SocialNetworkSuspendTinkerGraphTest | `"test_social_tinkergraph_suspend"` |
| Neo4jSocialNetworkTest | `"test_social_neo4j"` |
| Neo4jSocialNetworkSuspendTest | `"test_social_neo4j_suspend"` |
| MemgraphSocialNetworkTest | `"test_social_memgraph"` |
| MemgraphSocialNetworkSuspendTest | `"test_social_memgraph_suspend"` |

```
src/main/kotlin/io/bluetape4k/workshop/graph/social/
├── model/
│   └── ConnectionRecommendation.kt
├── schema/
│   └── SocialNetworkSchema.kt
└── service/
    ├── SocialNetworkService.kt
    └── SocialNetworkSuspendService.kt

src/test/kotlin/io/bluetape4k/workshop/graph/social/
├── seed/
│   └── SocialNetworkSeed.kt           # 시드 데이터 헬퍼 함수
├── AbstractSocialNetworkTest.kt       # blocking 서비스 테스트 스위트 (abstract)
├── AbstractSocialNetworkSuspendTest.kt # suspend 서비스 테스트 스위트 (abstract)
├── SocialNetworkTinkerGraphTest.kt    # TinkerGraph blocking
├── SocialNetworkSuspendTinkerGraphTest.kt # TinkerGraph suspend
├── Neo4jSocialNetworkTest.kt          # Neo4j blocking (@Tag("integration"))
├── Neo4jSocialNetworkSuspendTest.kt   # Neo4j suspend (@Tag("integration"))
├── MemgraphSocialNetworkTest.kt       # Memgraph blocking (@Tag("integration"))
└── MemgraphSocialNetworkSuspendTest.kt # Memgraph suspend (@Tag("integration"))
```

### 8.5 테스트 케이스 목록 (AbstractSocialNetworkTest)

| 카테고리 | 테스트 | 검증 사항 |
|---------|--------|----------|
| Vertex | `addPerson creates a new Person vertex` | label, properties 확인 |
| Vertex | `addPerson returns existing vertex on second call (idempotent)` | ID 동일성 |
| Vertex | `addCompany creates a new Company vertex` | label, properties 확인 |
| Edge | `connect creates bidirectional KNOWS edges` | A→B, B→A 양방향 확인 |
| Edge | `connect creates edges with identical properties on both directions` | since, strength 속성 대칭성 |
| Edge | `follow creates unidirectional FOLLOWS edge` | 단방향 확인 |
| Edge | `follow does not create reverse FOLLOWS edge` | Carol→Eve만, Eve→Carol은 없음 |
| Validation | `addPerson throws on blank personId` | `assertFailsWith<IllegalArgumentException>` |
| Validation | `getConnectionsWithinDegree throws on degree out of range` | degree=0, degree=7 모두 IllegalArgumentException |
| Validation | `recommendConnections throws on non-positive limit` | limit=0 → IllegalArgumentException |
| Query | `getDirectConnections returns 1st degree connections` | Alice → {Bob, Frank} |
| Query | `getDirectConnections does not include FOLLOWS targets` | Carol getDirectConnections: Eve 미포함 |
| Query | `getConnectionsWithinDegree returns up to Nth degree` | degree=2: {Bob, Frank, Carol, Dave} |
| Query | `getNthDegreeConnections returns exactly Nth degree` | N=2: {Carol, Dave} (Bob, Frank 제외) |
| Query | `getNthDegreeConnections with degree 1 matches direct connections` | degree=1 결과 = getDirectConnections 결과 |
| Path | `findConnectionPath returns shortest path` | Alice→Carol: length=2 |
| Path | `findConnectionPath returns null for disconnected vertices` | Frank→Eve: null (KNOWS 경로 없음) |
| Path | `findAllConnectionPaths returns all paths within depth` | Alice→Carol: 경로 수 >= 1 |
| Mutual | `findMutualConnections returns shared connections` | Alice-Carol mutual = {Bob} |
| Mutual | `findMutualConnections returns empty for no shared connections` | Alice-Eve = {} |
| FOAF | `recommendConnections returns FOAF candidates with mutual connections` | Alice 추천: Carol({Bob}), Dave({Bob}) 포함 |
| FOAF | `recommendConnections excludes direct connections` | Alice 추천에 Bob, Frank 미포함 |
| FOAF | `recommendConnections excludes self` | Alice 추천에 Alice 미포함 |
| FOAF | `recommendConnections excludes depth-3+ connections` | Alice 추천에 Grace(depth=3) 미포함 |
| Colleague | `findColleagues returns coworkers at same company` | Alice 동료: {Bob, Carol} (자신 제외) |
| Colleague | `findColleagues excludes self` | 결과에 Alice 미포함 |
| Colleague | `findColleagues includes past employees (isCurrent=false)` | 현재 + 과거 동료 모두 포함 검증 |
| Lifecycle | `initialize is idempotent` | 2회 호출 예외 없음 |
| Error | `query methods with nonexistent vertexId return empty result or null` | 빈 List 또는 null (예외 아님) |

---

## 9. Brainstorming

### 9.1 설계 리스크 / 실패 모드

**리스크 1: KNOWS 양방향 double-counting**
- `connect(A,B)` 후 `connect(B,A)` 호출 시 4개 edge 생성 (2개 중복).
- **완화**: edge mutator는 비멱등 (canonical 패턴). KDoc에 "callers must not call
  `connect(A,B)` and then `connect(B,A)` — `connect` already creates both directions"
  명시. 테스트 시드에서도 한 방향만 호출.

**리스크 2: `getNthDegreeConnections` 집합 차 연산의 성능**
- `neighbors(maxDepth=N)` 과 `neighbors(maxDepth=N-1)` 두 번 호출. N이 크면 비효율적.
- **완화**: 워크샵 규모(수십~수백 정점)에서는 무시 가능.
  프로덕션 용도라면 BFS level-tracking 커스텀 구현 필요하나 범위 밖.

**리스크 3: FOAF 추천의 소규모 그래프 불안정성**
- 3-5명 그래프에서 2촌이 1명뿐이면 추천 결과가 빈약.
- **완화**: 테스트 시드에 6명(Alice, Bob, Carol, Dave, Eve, Frank)을 배치해
  최소 2-3개의 FOAF 후보가 생기도록 토폴로지 설계.
  `recommendConnections`가 빈 리스트를 반환하는 것도 유효한 결과로 문서화.

### 9.2 거부한 대안

**대안: `linkedin-graph-examples` 직접 복사 + 워크샵 적응**
- linkedin-graph-examples를 그대로 복사하면 `name` 기반 검색 (find-or-create 아닌 항상
  create), suspend 서비스 없음, abstract test hierarchy 없음 등 canonical 워크샵 패턴과
  불일치.
- **거부 이유**: `abuser-detection`이 이 프로젝트의 canonical pattern이며,
  find-or-create 멱등성, dual service, abstract test base + concrete 3-backend 구조가
  확립되어 있다. linkedin-graph-examples는 도메인 모델(스키마 이름, KNOWS 양방향)만
  참조하고, 구조는 abuser-detection을 따르는 hybrid 접근법을 채택.

---

## 10. DoD (Definition of Done)

### 10.1 기능 DoD

- [ ] `SocialNetworkSchema.kt`: `PersonLabel`, `CompanyLabel`, `KnowsLabel`, `WorksAtLabel`,
      `FollowsLabel` 정의 완료
- [ ] `ConnectionRecommendation.kt`: data class 정의 + `Serializable` + `serialVersionUID`
- [ ] `SocialNetworkService.kt`: 모든 public method 구현
  - [ ] `initialize`, `addPerson`, `addCompany` (find-or-create)
  - [ ] `connect` (양방향 KNOWS, identical properties on both edges), `follow` (단방향), `addWorkExperience` (with `startDate`)
  - [ ] `getDirectConnections`, `getConnectionsWithinDegree`, `getNthDegreeConnections`
  - [ ] `findConnectionPath`, `findAllConnectionPaths`
  - [ ] `findMutualConnections`, `recommendConnections`
  - [ ] `findColleagues`
- [ ] `SocialNetworkSuspendService.kt`: blocking 서비스의 suspend/Flow mirror 구현

### 10.2 품질 DoD

- [ ] 모든 public method에 English KDoc (one-line summary + `@param` + code example)
- [ ] domain key 파라미터 (`personId`, `companyId`, `role`) 에 `requireNotBlank()` 검증
- [ ] numeric 파라미터: `strength` → `require(it in 1..10)`, `degree` → `require(it in 1..MAX_TRAVERSAL_DEPTH)`, `limit` → `requirePositiveNumber()`
- [ ] optional metadata (`title`, `location`, `since`, `startDate`, `industry`) 는 validation 없음 (빈 문자열 허용)
- [ ] `companion object : KLogging()` (blocking) / `KLoggingChannel()` (suspend)
- [ ] `data class`에 `Serializable` + `serialVersionUID` 구현
- [ ] `build.gradle.kts` — abuser-detection과 동일 의존성 구조
- [ ] Kover coverage >= 80% (TinkerGraph 테스트 기준)

### 10.3 테스트 DoD

- [ ] `AbstractSocialNetworkTest`: 26개 이상 테스트 케이스 (섹션 8.5 기준)
- [ ] `AbstractSocialNetworkSuspendTest`: blocking mirror + Flow 수집 검증
- [ ] TinkerGraph 테스트: `./gradlew :graph-social-network:test` 통과
- [ ] Neo4j + Memgraph 테스트: `./gradlew :graph-social-network:integrationTest` 통과
- [ ] `src/test/resources/junit-platform.properties` 존재
- [ ] `src/test/resources/logback-test.xml` 존재
- [ ] `SocialNetworkSeed.kt`: 섹션 8.1 토폴로지 구현 (7인: Alice, Bob, Carol, Dave, Eve, Frank, Grace + 2개 회사)

### 10.4 문서 DoD

- [ ] README.md (English) — Architecture, Features, Usage, Build commands
- [ ] README.ko.md — README.md 한국어 버전
- [ ] Architecture diagram: `docs/images/readme-diagrams/` 하위 SVG+PNG

---

## 11. 리스크 및 완화 (요약)

| # | 리스크 | 영향 | 완화 |
|---|--------|------|------|
| 1 | KNOWS 양방향 double-counting | 중복 edge → 잘못된 neighbor count | KDoc 경고 + 테스트 시드 단방향 호출 |
| 2 | getNthDegree 집합 차 성능 | N 증가 시 2x traversal | 워크샵 규모 한정; 프로덕션은 BFS level-tracking |
| 3 | FOAF 소규모 그래프 불안정 | 추천 결과 빈약 | 7인 테스트 시드(Grace 추가); 빈 리스트 허용 문서화 |
| 4 | Memgraph weighted path 미지원 | `PathOptions.weightProperty` 사용 불가 | 비가중치 경로만 사용 → 3-backend 동일 테스트 |
| 5 | `connect()` 비멱등 | 중복 호출 시 edge 4개 | KDoc 명시; 향후 upsert/merge 확장 가능 |
| 6 | `connect()` 부분 실패 | 비대칭 KNOWS 상태 | Known Limitation; `@BeforeEach dropGraph()`로 초기화 |
| 7 | `findAllConnectionPaths` 경로 폭발 | 조합 폭발 → 타임아웃 | `maxDepth` 파라미터(기본 5) + require(maxDepth ≤ MAX_TRAVERSAL_DEPTH) |
| 8 | FOAF N+1 쿼리 | M+2 round-trip | 워크샵 허용; 프로덕션은 단일 Cypher/Gremlin 쿼리 |
| 9 | `degree` 무제한 | 전체 그래프 traversal → 타임아웃 | `require(degree in 1..MAX_TRAVERSAL_DEPTH)` (MAX=6) |
| 10 | neighbors() seed 반환 | FOAF/N-degree 결과에 자기 자신 포함 | 모든 알고리즘에서 `- {seed}` 명시적 제거 |

---

## 12. 초안 태스크 목록 (Step 3 plan 용)

| # | 태스크 | 의존 | 예상 |
|---|--------|------|------|
| T1 | 모듈 등록: `settings.gradle.kts` + `build.gradle.kts` | 없음 | 작음 |
| T2 | 테스트 리소스: `junit-platform.properties` + `logback-test.xml` | T1 | 작음 |
| T3 | `SocialNetworkSchema.kt` — Vertex/Edge labels | T1 | 작음 |
| T4 | `ConnectionRecommendation.kt` — data class | T3 | 작음 |
| T5 | `SocialNetworkService.kt` — blocking 서비스 전체 구현 | T3, T4 | 중간 |
| T6 | `SocialNetworkSuspendService.kt` — suspend 서비스 전체 구현 | T5 | 중간 |
| T7 | `SocialNetworkSeed.kt` — 테스트 시드 유틸 | T5 | 작음 |
| T8 | `AbstractSocialNetworkTest.kt` — blocking 테스트 스위트 (27개+ 케이스, unique graphName 필수) | T5, T7 | 중간 |
| T9 | `AbstractSocialNetworkSuspendTest.kt` — suspend 테스트 스위트 (Flow→List 수집 검증) | T6, T7 | 중간 |
| T10 | `SocialNetworkTinkerGraphTest.kt` + suspend 버전 | T8, T9 | 작음 |
| T11 | `Neo4jSocialNetworkTest.kt` + suspend 버전 | T8, T9 | 작음 |
| T12 | `MemgraphSocialNetworkTest.kt` + suspend 버전 | T8, T9 | 작음 |
| T13 | TinkerGraph 테스트 실행 및 통과 확인 | T10 | 작음 |
| T14 | 통합 테스트 실행 및 통과 확인 | T11, T12 | 중간 |
| T15 | README.md + README.ko.md 작성 | T13 | 중간 |
| T16 | Architecture diagram (SVG+PNG) 생성 | T15 | 작음 |

---

## Appendix A. Step 2-R 이터레이션 로그

| Round | Phase 1 (4×sonnet/haiku) | 6-tier (opus) | Phase 2 Critic (opus) | Phase 3 Codex | Spec 반영 |
|-------|--------------------------|---------------|-----------------------|---------------|-----------|
| 1 | HIGH: 9건 (Developer 4, User/caller 2, Ops/SRE 3, Security 2) | P0: 1, P1: 9, P2: 7, P3: 2 | HIGH: 6건 (H1-H6) | HIGH: 1, MEDIUM: 6, LOW: 3 | addWorkExperience startDate, connect KDoc+partial failure+symmetry, findAllConnectionPaths maxDepth, degree bound, validation 정책, parameter rename, neighbors() contract fix, seed topology(Grace 추가), test cases 19→27개, graphName uniqueness, findColleagues dedup, companion object 통합, FOAF zero-mutual 필터 제거(vacuous), tie-breaking personId으로 변경 |
| **P0/P1 잔여** | **0** | **0** | **0** | **0** | **수렴 — 진행 가능** |

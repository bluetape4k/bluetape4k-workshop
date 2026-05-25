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
| `KnowsLabel` | `"KNOWS"` | Person -> Person | `since` (ISO date), `strength` (1-10) | **양방향** (A->B, B->A 두 edge 생성) |
| `WorksAtLabel` | `"WORKS_AT"` | Person -> Company | `role`, `startDate`, `isCurrent` | 단방향 |
| `FollowsLabel` | `"FOLLOWS"` | Person -> Person | (없음) | 단방향 |

---

## 5. API 설계

### 5.1 SocialNetworkService (blocking)

```kotlin
class SocialNetworkService(
    private val ops: GraphOperations,
    private val graphName: String = "social_network",
) {
    companion object : KLogging()

    // ── Lifecycle ──
    fun initialize()

    // ── Vertex mutators (find-or-create by domain key) ──
    fun addPerson(personId: String, name: String, title: String = "", location: String = ""): GraphVertex
    fun addCompany(companyId: String, name: String, industry: String = "", location: String = ""): GraphVertex

    // ── Edge mutators ──
    fun connect(personId1: GraphElementId, personId2: GraphElementId, since: String = "", strength: Int = 5)
    fun follow(followerId: GraphElementId, targetId: GraphElementId)
    fun addWorkExperience(personId: GraphElementId, companyId: GraphElementId, role: String, isCurrent: Boolean = false)

    // ── Query: 인맥 탐색 ──
    fun getDirectConnections(personId: GraphElementId): List<GraphVertex>
    fun getConnectionsWithinDegree(personId: GraphElementId, degree: Int): List<GraphVertex>
    fun getNthDegreeConnections(personId: GraphElementId, degree: Int): List<GraphVertex>
    fun findConnectionPath(fromId: GraphElementId, toId: GraphElementId): GraphPath?
    fun findAllConnectionPaths(fromId: GraphElementId, toId: GraphElementId): List<GraphPath>

    // ── Query: 공통 인맥 & 추천 ──
    fun findMutualConnections(personId1: GraphElementId, personId2: GraphElementId): List<GraphVertex>
    fun recommendConnections(personId: GraphElementId, limit: Int = 10): List<ConnectionRecommendation>

    // ── Query: 직장 관련 ──
    fun findColleagues(personId: GraphElementId): List<GraphVertex>
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
    suspend fun connect(personId1: GraphElementId, personId2: GraphElementId, since: String = "", strength: Int = 5)
    suspend fun follow(followerId: GraphElementId, targetId: GraphElementId)
    suspend fun addWorkExperience(personId: GraphElementId, companyId: GraphElementId, role: String, isCurrent: Boolean = false)

    // ── Query: 인맥 탐색 ──
    suspend fun getDirectConnections(personId: GraphElementId): List<GraphVertex>
    suspend fun getConnectionsWithinDegree(personId: GraphElementId, degree: Int): List<GraphVertex>
    suspend fun getNthDegreeConnections(personId: GraphElementId, degree: Int): List<GraphVertex>
    suspend fun findConnectionPath(fromId: GraphElementId, toId: GraphElementId): GraphPath?
    suspend fun findAllConnectionPaths(fromId: GraphElementId, toId: GraphElementId): List<GraphPath>

    // ── Query: 공통 인맥 & 추천 ──
    suspend fun findMutualConnections(personId1: GraphElementId, personId2: GraphElementId): List<GraphVertex>
    suspend fun recommendConnections(personId: GraphElementId, limit: Int = 10): List<ConnectionRecommendation>

    // ── Query: 직장 관련 ──
    suspend fun findColleagues(personId: GraphElementId): List<GraphVertex>
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

**목적**: 공통 인맥이 많은 2촌 인맥을 추천한다.

**알고리즘**:
```
1. directFriends = neighbors(seed, KNOWS, OUTGOING, depth=1)
2. friendsOfFriends = neighbors(seed, KNOWS, OUTGOING, depth=2)
3. foafCandidates = friendsOfFriends - directFriends - {seed}
4. 각 candidate에 대해:
   a. candidateFriends = neighbors(candidate, KNOWS, OUTGOING, depth=1)
   b. mutualConnections = directFriends ∩ candidateFriends
   c. mutualCount = |mutualConnections|
5. mutualCount 내림차순 정렬
6. 상위 limit개 반환
```

**핵심 결정**:
- Step 3에서 directFriends를 명시적으로 제외해야 이미 연결된 사람을 추천하지 않는다.
- Step 4b에서 실제 mutual vertex 목록을 수집해 `ConnectionRecommendation.mutualConnections`에
  저장한다. count만 반환하는 것보다 UI 표시에 유용하다.
- `mutualCount = 0`인 후보는 결과에서 제외한다 (공통 인맥 없는 2촌은 추천 가치 낮음).
- **반환 타입**: blocking/suspend 모두 `List<ConnectionRecommendation>`을 반환한다.
  정렬(Step 5)이 전체 후보 수집 후에만 가능하므로 `Flow`는 의미적으로 부적합하다
  (내부 버퍼링 후 정렬하는 Flow는 lazy streaming의 이점이 없다).

### 6.2 N차 연결 탐색

**`getConnectionsWithinDegree(personId, degree)`**:
```
neighbors(personId, KNOWS, OUTGOING, maxDepth=degree)
```
- `NeighborOptions.maxDepth`가 depth > 1을 지원함을 확인 완료 (모든 3개 백엔드).
- 반환값에 seed 자신은 포함되지 않음 (`neighbors()` 계약).

**`getNthDegreeConnections(personId, degree)`**:
```
allWithin = neighbors(personId, KNOWS, OUTGOING, maxDepth=degree)
closer    = if (degree > 1) neighbors(personId, KNOWS, OUTGOING, maxDepth=degree-1) else emptyList()
result    = allWithin - closer - {seed}  // ID 기준 집합 차
```
- 정확히 N촌에 해당하는 사람만 추출.
- `degree=1`이면 `closer`는 빈 리스트이므로 `getDirectConnections`와 동일 결과.
- 양방향 KNOWS이므로 `Direction.OUTGOING`만으로 전체 인맥 탐색 가능.

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
KDoc에 "callers must avoid duplicate calls" 명시.

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
       │                      │                    │
       │                 KNOWS│               FOLLOWS
       │                      │                    │
       KNOWS             [Person: Dave]            ▼
       │                      │           [Person: Eve]
       ▼                 WORKS_AT
[Person: Frank]               │
                              ▼
                    [Company: Acme]
```

**토폴로지 의도**:
- Alice-Bob: 1촌 (KNOWS 양방향)
- Bob-Carol: 1촌 (KNOWS 양방향)
- Alice-Carol: 2촌 (Alice → Bob → Carol)
- Alice-Frank: 1촌 (KNOWS 양방향)
- Bob-Dave: 1촌 (KNOWS 양방향)
- Carol → Eve: FOLLOWS (단방향, KNOWS 아님)
- Alice, Bob: Bluetape4k 재직 → 동료
- Carol: Bluetape4k 재직 → Alice/Bob과 동료
- Dave: Acme 재직 → Alice/Bob/Carol과 비동료

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
├── AbstractSocialNetworkTest.kt       # blocking 서비스 테스트 스위트
├── AbstractSocialNetworkSuspendTest.kt # suspend 서비스 테스트 스위트
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
| Edge | `follow creates unidirectional FOLLOWS edge` | 단방향 확인 |
| Query | `getDirectConnections returns 1st degree connections` | Alice → {Bob, Frank} |
| Query | `getConnectionsWithinDegree returns up to Nth degree` | degree=2: {Bob, Frank, Carol, Dave} |
| Query | `getNthDegreeConnections returns exactly Nth degree` | N=2: {Carol, Dave} (Bob, Frank 제외) |
| Query | `getNthDegreeConnections with degree 1 matches direct connections` | degree=1 결과 = getDirectConnections 결과 |
| Path | `findConnectionPath returns shortest path` | Alice→Carol: length=2 |
| Path | `findAllConnectionPaths returns all paths within depth` | Alice→Carol: 경로 수 >= 1 |
| Mutual | `findMutualConnections returns shared connections` | Alice-Carol mutual = {Bob} |
| Mutual | `findMutualConnections returns empty for no shared connections` | Alice-Eve = {} |
| FOAF | `recommendConnections returns FOAF by mutual count descending` | Alice 추천: Carol(mutual=Bob) |
| FOAF | `recommendConnections excludes direct connections` | Alice 추천에 Bob, Frank 미포함 |
| FOAF | `recommendConnections excludes self` | Alice 추천에 Alice 미포함 |
| Colleague | `findColleagues returns coworkers at same company` | Alice 동료: {Bob, Carol} (자신 제외) |
| Colleague | `findColleagues excludes self` | 결과에 Alice 미포함 |
| Lifecycle | `initialize is idempotent` | 2회 호출 예외 없음 |

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
  - [ ] `connect` (양방향 KNOWS), `follow` (단방향), `addWorkExperience`
  - [ ] `getDirectConnections`, `getConnectionsWithinDegree`, `getNthDegreeConnections`
  - [ ] `findConnectionPath`, `findAllConnectionPaths`
  - [ ] `findMutualConnections`, `recommendConnections`
  - [ ] `findColleagues`
- [ ] `SocialNetworkSuspendService.kt`: blocking 서비스의 suspend/Flow mirror 구현

### 10.2 품질 DoD

- [ ] 모든 public method에 English KDoc (one-line summary + `@param` + code example)
- [ ] 모든 입력 파라미터에 `requireNotBlank()` / `requirePositiveNumber()` 검증
- [ ] `companion object : KLogging()` (blocking) / `KLoggingChannel()` (suspend)
- [ ] `data class`에 `Serializable` + `serialVersionUID` 구현
- [ ] `build.gradle.kts` — abuser-detection과 동일 의존성 구조
- [ ] Kover coverage >= 80% (TinkerGraph 테스트 기준)

### 10.3 테스트 DoD

- [ ] `AbstractSocialNetworkTest`: 19개 이상 테스트 케이스 (섹션 8.5 기준)
- [ ] `AbstractSocialNetworkSuspendTest`: blocking mirror + Flow 수집 검증
- [ ] TinkerGraph 테스트: `./gradlew :graph-social-network:test` 통과
- [ ] Neo4j + Memgraph 테스트: `./gradlew :graph-social-network:integrationTest` 통과
- [ ] `src/test/resources/junit-platform.properties` 존재
- [ ] `src/test/resources/logback-test.xml` 존재
- [ ] `SocialNetworkSeed.kt`: 섹션 8.1 토폴로지 구현

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
| 3 | FOAF 소규모 그래프 불안정 | 추천 결과 빈약 | 6인 테스트 시드; 빈 리스트 허용 문서화 |
| 4 | Memgraph weighted path 미지원 | `PathOptions.weightProperty` 사용 불가 | 비가중치 경로만 사용 → 3-backend 동일 테스트 |
| 5 | `connect()` 비멱등 | 중복 호출 시 edge 4개 | KDoc 명시; 향후 upsert/merge 확장 가능 |

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
| T8 | `AbstractSocialNetworkTest.kt` — blocking 테스트 스위트 | T5, T7 | 중간 |
| T9 | `AbstractSocialNetworkSuspendTest.kt` — suspend 테스트 스위트 | T6, T7 | 중간 |
| T10 | `SocialNetworkTinkerGraphTest.kt` + suspend 버전 | T8, T9 | 작음 |
| T11 | `Neo4jSocialNetworkTest.kt` + suspend 버전 | T8, T9 | 작음 |
| T12 | `MemgraphSocialNetworkTest.kt` + suspend 버전 | T8, T9 | 작음 |
| T13 | TinkerGraph 테스트 실행 및 통과 확인 | T10 | 작음 |
| T14 | 통합 테스트 실행 및 통과 확인 | T11, T12 | 중간 |
| T15 | README.md + README.ko.md 작성 | T13 | 중간 |
| T16 | Architecture diagram (SVG+PNG) 생성 | T15 | 작음 |

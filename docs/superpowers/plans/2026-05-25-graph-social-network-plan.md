# 계획: graph/social-network 워크숍 모듈

**날짜**: 2026-05-25
**지점**: `feat/graph-social-network`
**사양**: `docs/superpowers/specs/2026-05-25-graph-social-network-design.md`
**모듈**: `graph/social-network` — Gradle 모듈 이름: `graph-social-network`
**표준 패턴**: `graph/abuser-detection/`
**스택**: Kotlin 2.3.20, Java 25, bluetape4k 1.5.0-Beta2, bluetape4k-graph(BOM을 통해)

---

## 작업에 인코딩된 주요 사양 결정

1. **자동 등록**: `settings.gradle.kts` 27행에는 이미 `includeModules("graph", false, true)`이 있습니다. `includeModules` 기능은 `graph/` 아래의 모든 하위 디렉터리를 자동으로 검색합니다. `graph/social-network/build.gradle.kts`를 생성하는 것으로 충분합니다. settings.gradle.kts를 변경할 필요가 없습니다. 결과 모듈 이름은 `:graph-social-network`입니다.

2. **KNOWS 양방향**: `connect(A,B)`은 IDENTICAL 속성을 ​​사용하여 TWO방향 모서리(A→B, B→A)를 생성합니다. 발신자는 `connect(A,B)` 후에 NOT `connect(B,A)`을 호출해야 합니다.

3. **멱등성 찾기 또는 생성**: `addPerson`/`addCompany` 도메인 키(`personId`/`companyId`)로만 찾습니다. 동일한 키를 사용한 두 번째 호출은 속성을 업데이트하지 않고 기존 꼭짓점을 반환합니다.

4. **시드 중앙화**: AbstractSocialNetworkTest의 `@BeforeEach`는 dropGraph + 초기화 후에 `seedSocialNetwork(service)`을 호출합니다. 이렇게 하면 모두 동일한 토폴로지가 필요한 29개 테스트 사례의 상용구가 줄어듭니다.

5. **FOAF 타이 브레이킹**: 결정론적 크로스 백엔드 순서 지정을 위한 personId 도메인 키 오름차순(NOT 백엔드 GraphElementId).

6. **neighbors() 시드 제외**: 모든 알고리즘(FOAF, N 차수, findColleagues)은 결과에서 명시적으로 `{seed}`을 빼야 합니다. `neighbors()`은(는) 깊이 >= 2에서 시드 제외를 보장하지 않습니다.

7. **일시 중지 서비스는 Flow이 아닌 목록을 반환합니다. **: FOAF 정렬에는 전체 수집이 필요합니다. `SocialNetworkSuspendService`의 모든 쿼리 메소드는 내부적으로 `Flow<GraphVertex>.toList()`을 수집하고 `List`를 반환합니다.

8. **구체적인 테스트 클래스당 고유한 graphName**: 여러 테스트 클래스가 Neo4j/Memgraph 컨테이너를 공유할 때 상태 충돌을 방지합니다.

9. **ID 기반 집합 연산**: `GraphVertex` 동등성은 NOT ID 기반으로 보장됩니다. 항상 `Set<GraphElementId>`를 사용하여 `.id`을 통해 비교하세요.

10. **검증 정책**: `requireNotBlank()` 도메인 키(`personId`, `companyId`, `role`)에만 적용됩니다. 선택적 메타데이터는 빈 문자열을 허용합니다. 숫자: `strength in 1..10`, `degree in 1..MAX_TRAVERSAL_DEPTH`, `limit > 0`.

---

## 1단계 - 모듈 비계

### T1: 모듈 등록 및 빌드 구성

- **복잡성**: 낮음
- **파일**: `graph/social-network/build.gradle.kts`
- **종속성**: 없음
- **참고**:
  - `settings.gradle.kts` 변경이 필요하지 않습니다. `includeModules("graph", false, true)`은 하위 디렉터리를 자동 검색합니다.
  - `graph/abuser-detection/build.gradle.kts` 구조를 정확하게 복사하세요.

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

  - 종속성(순서가 중요함 - BOM이 먼저 와야 함):
    - `implementation(platform(libs.bluetape4k.graph.bom))`
    - `implementation(libs.bluetape4k.graph.core)` + `implementation(libs.bluetape4k.graph.tinkerpop)`
    - `compileOnly(libs.bluetape4k.graph.neo4j)` + `compileOnly(libs.bluetape4k.graph.memgraph)`
    - `implementation(libs.bluetape4k.logging)` + `implementation(libs.bluetape4k.coroutines)`
    - `implementation(libs.kotlinx.coroutines.core.lib)` + `testImplementation(libs.kotlinx.coroutines.test.lib)`
    - `testImplementation(project(":shared"))` + `testImplementation(libs.bluetape4k.junit5)` + `testImplementation(libs.bluetape4k.testcontainers)` + `testImplementation(libs.testcontainers.neo4j)` + `testImplementation(libs.bluetape4k.assertions)` + `testImplementation(libs.mockk)`
  - **알았어**: 구성의 `.get()`은 Kotlin DSL의 REQUIRED입니다.
  - **확인**: `./gradlew :graph-social-network:dependencies`이(가) 오류 없이 실행됩니다.

---

### T2: 테스트 리소스

- **복잡성**: 낮음
- **파일**:
  - `graph/social-network/src/test/resources/junit-platform.properties`
  - `graph/social-network/src/test/resources/logback-test.xml`
- **종속성**: T1

- `junit-platform.properties` (남용자 탐지와 정확히 일치함):
```properties
junit.jupiter.extensions.autodetection.enabled=true
junit.jupiter.testinstance.lifecycle.default=per_class

junit.jupiter.execution.parallel.enabled=false
junit.jupiter.execution.parallel.mode.default=same_thread
junit.jupiter.execution.parallel.mode.classes.default=concurrent
```
  - **NEVER 추가** `junit.jupiter.tags.exclude=integration` 여기에 — 태그 제외는 `build.gradle.kts tasks.test { excludeTags }`에서만 수행됩니다.

- `logback-test.xml` — 남용자 감지에서 복사, 로거 변경:
```xml
<logger name="io.bluetape4k.workshop.graph.social" level="DEBUG"/>
```

---

## 2단계 — 스키마 및 도메인 모델

### T3: 스키마 정의

- **복잡성**: 낮음
- **파일**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/schema/SocialNetworkSchema.kt`
- **종속성**: T1
- **참고**:
  - 학대자 탐지 `AbuserDetectionSchema.kt` 패턴을 정확하게 따르세요.
  - 그래프 속성은 `Map<String, String>`입니다. `strength: Int` 및 `isCurrent: Boolean`도 문자열로 저장됩니다. 서비스 계층에서 변환.

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

### T4: 도메인 모델 — ConnectionRecommendation

- **복잡성**: 낮음
- **파일**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/model/ConnectionRecommendation.kt`
- **종속성**: T3

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

## 3단계 - 서비스 구현

### T5: SocialNetworkService (차단)

- **복잡성**: 높음
- **파일**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/service/SocialNetworkService.kt`
- **종속성**: T3, T4

**주요 패턴**:

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

    // addCompany — find-or-create by companyId domain key
    fun addCompany(companyId: String, name: String, industry: String = "", location: String = ""): GraphVertex {
        companyId.requireNotBlank("companyId")
        return ops.findVerticesByLabel(CompanyLabel.label, mapOf(CompanyLabel.companyId.name to companyId))
            .firstOrNull()
            ?: ops.createVertex(
                CompanyLabel.label,
                mapOf(
                    CompanyLabel.companyId.name to companyId,
                    CompanyLabel.name.name to name,
                    CompanyLabel.industry.name to industry,
                    CompanyLabel.location.name to location,
                )
            )
    }

    fun getDirectConnections(personVertexId: GraphElementId): List<GraphVertex> {
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
    }

    // connect() — TWO directed edges, SAME properties on both
    // Note: requireInRange is available in bluetape4k-core (io.bluetape4k.support.requireInRange)
    fun connect(personVertexId1: GraphElementId, personVertexId2: GraphElementId, since: String = "", strength: Int = 5) {
        strength.requireInRange(1, 10, "strength")  // bluetape4k extension: inclusive range check
        val props = mapOf(
            KnowsLabel.since.name to since,
            KnowsLabel.strength.name to strength.toString(),
        )
        ops.createEdge(personVertexId1, personVertexId2, KnowsLabel.label, props)
        ops.createEdge(personVertexId2, personVertexId1, KnowsLabel.label, props)  // same props
    }

    fun getConnectionsWithinDegree(personVertexId: GraphElementId, degree: Int): List<GraphVertex> {
        degree.requireInRange(1, MAX_TRAVERSAL_DEPTH, "degree")
        return ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree))
            .filter { it.id != personVertexId }  // explicit seed exclusion — neighbors() does not guarantee this
    }

    fun getNthDegreeConnections(personVertexId: GraphElementId, degree: Int): List<GraphVertex> {
        degree.requireInRange(1, MAX_TRAVERSAL_DEPTH, "degree")
        val allWithin = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree))
            .filter { it.id != personVertexId }
        val closerIds = if (degree > 1) {
            ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, degree - 1))
                .filter { it.id != personVertexId }  // spec formula: closer set also excludes seed
                .map { it.id }.toSet()
        } else emptySet()
        return allWithin.filter { it.id !in closerIds }
    }

    fun follow(personVertexId1: GraphElementId, personVertexId2: GraphElementId) {
        ops.createEdge(personVertexId1, personVertexId2, FollowsLabel.label, emptyMap())
        // FOLLOWS is unidirectional — do NOT create reverse edge
    }

    fun addWorkExperience(
        personVertexId: GraphElementId,
        companyVertexId: GraphElementId,
        role: String,
        startDate: String = "",
        isCurrent: Boolean = true,
    ) {
        role.requireNotBlank("role")
        ops.createEdge(
            personVertexId, companyVertexId, WorksAtLabel.label,
            mapOf(
                WorksAtLabel.role.name to role,
                WorksAtLabel.startDate.name to startDate,
                WorksAtLabel.isCurrent.name to isCurrent.toString(),
            )
        )
    }

    /**
     * Finds the shortest KNOWS path between two persons.
     *
     * @param fromVertexId start vertex ID
     * @param toVertexId end vertex ID
     * @return shortest path, or null if no path exists
     */
    fun findConnectionPath(fromVertexId: GraphElementId, toVertexId: GraphElementId): GraphPath? {
        return ops.shortestPath(fromVertexId, toVertexId, PathOptions(KnowsLabel.label, Direction.OUTGOING))
    }

    /**
     * Finds all KNOWS paths between two persons up to maxDepth hops.
     *
     * **WARNING**: Path enumeration is exponential in dense graphs. Keep maxDepth ≤ 6.
     *
     * @param fromVertexId start vertex ID
     * @param toVertexId end vertex ID
     * @param maxDepth maximum hop count (default 5, max MAX_TRAVERSAL_DEPTH)
     */
    fun findAllConnectionPaths(
        fromVertexId: GraphElementId,
        toVertexId: GraphElementId,
        maxDepth: Int = 5,
    ): List<GraphPath> {
        maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")
        return ops.allPaths(fromVertexId, toVertexId, PathOptions(KnowsLabel.label, Direction.OUTGOING, maxDepth))
    }

    fun findMutualConnections(personVertexId1: GraphElementId, personVertexId2: GraphElementId): List<GraphVertex> {
        val friends1 = ops.neighbors(personVertexId1, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
            .map { it.id }.toSet()
        val friends2 = ops.neighbors(personVertexId2, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
        return friends2.filter { it.id in friends1 }
    }

    /**
     * Recommends FOAF (Friend-of-a-Friend) connections sorted by mutual connection count descending,
     * then personId ascending for stable tie-breaking across backends.
     *
     * **N+1 Warning**: Executes M+2 round-trips (M = FOAF candidate count, +2 for direct friends + per-candidate friends).
     * Acceptable for workshop; production should use a single Cypher/Gremlin query. See spec Risk #8.
     */
    // FOAF — spec section 6.1
    fun recommendConnections(personVertexId: GraphElementId, limit: Int = 10): List<ConnectionRecommendation> {
        limit.requirePositiveNumber("limit")
        val directFriends = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 1))
        val directFriendIds = directFriends.map { it.id }.toSet()
        val foafCandidates = ops.neighbors(personVertexId, NeighborOptions(KnowsLabel.label, Direction.OUTGOING, 2))
            .filter { it.id != personVertexId && it.id !in directFriendIds }
            .distinctBy { it.id }  // depth=2 traversal may return duplicates via different paths
        // mutualCount=0 filter omitted — depth=2 FOAF candidates always have ≥1 mutual connection by definition
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

**중요한 문제**:
- `connect()`: SAME `props` 양쪽 가장자리에 대한 맵(속성 대칭)
- FOAF: `distinctBy { it.id }` 후보에 대해 — 깊이=2 순회는 다른 경로를 통해 중복을 반환합니다.
- 동점 결정: `personId` `properties` 지도, NOT `GraphElementId`.
- `findColleagues`: `distinctBy { it.id }` — 여러 회사의 동일한 사람이 중복 제거 없이 중복 항목을 반환합니다.

---

### T6: SocialNetworkSuspendService (일시 중지)

- **복잡성**: 중간
- **파일**: `graph/social-network/src/main/kotlin/io/bluetape4k/workshop/graph/social/service/SocialNetworkSuspendService.kt`
- **종속성**: T5

**T5과의 주요 차이점**:
- `companion object : KLoggingChannel()` (NOT `KLogging()`).
- 모든 메소드에는 `suspend` 키워드가 있습니다.
- `ops.neighbors()`은 `Flow<GraphVertex>`을 반환 → `.toList()`를 호출해야 합니다.
- `ops.findVerticesByLabel()`은 `Flow<GraphVertex>`을 반환하고 → `import kotlinx.coroutines.flow.firstOrNull`를 사용합니다.
- 일시 중지 호출에는 절대로 `runCatching {}`을 사용하지 마세요. CancellationException은 반드시 전파되어야 합니다.

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

## 4단계 - 테스트 인프라

### T7a: SocialNetworkSeed (과부하 차단)

- **복잡성**: 중간
- **파일**: `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/seed/SocialNetworkSeed.kt`
- **종속성**: T5만(T8은 T5 + T7a가 완료되자마자 T6을 기다리지 않고 시작할 수 있음)

```kotlin
import java.io.Serializable

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
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

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

---

### T7b: SocialNetworkSeed (suspend overload)

- **Complexity**: low
- **File**: Same file as T7a (`seed/SocialNetworkSeed.kt`) — add suspend overload
- **Dependencies**: T6 only (T9 can start as soon as T6 + T7b are done)
- **Note**: Use `runSuspendIO` in tests (not `runTest`) — IO-bound Testcontainers operations. `runSuspendIO` is `withContext(Dispatchers.IO)` equivalent from bluetape4k-junit5.

```kotlin
// 일시중단 오버로드 — 동일한 토폴로지, 각 호출은 일시중단됩니다.
일시 중지 재미 seedSocialNetwork(서비스: SocialNetworkSuspendService): SocialNetworkSeed {
    val alice = 서비스.addPerson("앨리스", "앨리스", "엔지니어", "서울")
    val bob = service.addPerson("bob", "Bob", "Manager", "Seoul")
    val carol = service.addPerson("carol", "Carol", "디자이너", "부산")
    val dave = 서비스.addPerson("dave", "Dave", "엔지니어", "인천")
    val eve = service.addPerson("eve", "Eve", "Analyst", "Seoul")
    val Frank = 서비스.addPerson("frank", "Frank", "Engineer", "대전")
    val Grace = service.addPerson("은혜", "은혜", "PM", "서울")

    val bluetape4k = service.addCompany("bluetape4k", "Bluetape4k", "기술", "서울")
    val acme = 서비스.addCompany("acme", "Acme", "제조", "인천")

    service.connect(alice.id, bob.id, 이후 = "2020-01-01", 강도 = 8)
    service.connect(bob.id, carol.id, 이후 = "2021-03-15", 강도 = 6)
    service.connect(alice.id, Frank.id, 이후 = "2019-06-01", 강도 = 7)
    service.connect(bob.id, dave.id, 이후 = "2022-02-01", 강도 = 5)
    service.connect(dave.id, Grace.id, 이후 = "2023-01-01", 강도 = 4)

    service.follow(carol.id, eve.id)

    service.addWorkExperience(alice.id, bluetape4k.id, "Senior Engineer", "2020-01-01", isCurrent = true)
    service.addWorkExperience(bob.id, bluetape4k.id, "Manager", "2019-06-01", isCurrent = true)
    service.addWorkExperience(carol.id, bluetape4k.id, "Designer", "2021-01-01", isCurrent = false)
    service.addWorkExperience(dave.id, acme.id, "Engineer", "2022-01-01", isCurrent = true)

    반환 SocialNetworkSeed(alice, bob, carol, dave, eve, Frank, Grace, bluetape4k, acme)
}
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
- **Dependencies**: T5, T7a

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
추상 클래스 AbstractSocialNetworkTest {
    보호된 추상 값 graphName: 문자열
    보호된 추상 값 작업: GraphOperations
    보호된 추상 Val 서비스: SocialNetworkService

    보호된 lateinit var 시드: SocialNetworkSeed

    @BeforeEach
    재미있다 setUp() {
        ops.dropGraph(graphName) // 표준 패턴: 직접 호출, 아니요 runCatching
        서비스.초기화()
        시드 = seedSocialNetwork(서비스)
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
| 8b | Validation | `addCompany throws on blank companyId` | `assertFailsWith<IllegalArgumentException>` |
| 8c | Validation | `addWorkExperience throws on blank role` | `assertFailsWith<IllegalArgumentException>` |
| 8d | Validation | `connect throws on strength outside 1..10` | strength=0, strength=11 both throw |
| 9 | Validation | `getConnectionsWithinDegree throws on degree out of range` | degree=0 and degree=7 throw |
| 9b | Validation | `findAllConnectionPaths throws on maxDepth exceeding MAX_TRAVERSAL_DEPTH` | maxDepth=7 throws |
| 10 | Validation | `recommendConnections throws on non-positive limit` | limit=0 throws |
| 11 | Query | `getDirectConnections returns 1st degree connections` | Alice → names contain "Bob", "Frank" |
| 12 | Query | `getDirectConnections does not include FOLLOWS targets` | Carol's result does NOT contain Eve |
| 13 | Query | `getConnectionsWithinDegree returns up to Nth degree` | Alice degree=2 → {Bob, Frank, Carol, Dave}; also assert `result.map { it.id } shouldNotContain seed.alice.id` |
| 14 | Query | `getNthDegreeConnections returns exactly Nth degree` | Alice N=2 → {Carol, Dave} (not Bob, Frank); also assert `result.map { it.id } shouldNotContain seed.alice.id` |
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
- FOAF #21: use `shouldContainExactly(listOf("carol", "dave"))` by personId — service guarantees deterministic order (personId ascending tiebreak). Test #13/#14 must also add `shouldNotContain(seed.alice)` to verify seed exclusion.
  ```kotlin
  // #21 정확한 순서 검증:
  val personIds = 결과.map { it.person.properties[PersonLabel.personId.name] }
  personIds shouldContainExactly listOf("캐롤", "데이브")
  // #13/#14 제외 회귀 가드:
  result.map { it.id } shouldNotContain seed.alice.id
  ```
- Names: `results.map { it.properties[PersonLabel.name.name] }`.
- `assertFailsWith<IllegalArgumentException>` for validation (NOT `assertThrows`, NOT `invoking/shouldThrow`).

---

### T9: AbstractSocialNetworkSuspendTest (Suspend)

- **Complexity**: high
- **File**: `graph/social-network/src/test/kotlin/io/bluetape4k/workshop/graph/social/AbstractSocialNetworkSuspendTest.kt`
- **Dependencies**: T6, T7b
- **Notes**: Mirror of T8 with `runSuspendIO { }` wrapping every test body. Same 34 test cases (see updated table).
  `runSuspendIO` preferred over `runTest` here because tests invoke real IO-bound backend operations (TinkerGraph, Neo4j, Memgraph). `runSuspendIO` = `withContext(Dispatchers.IO)` from bluetape4k-junit5; `runTest` uses `TestCoroutineScheduler` (virtual time) which is inappropriate for real I/O.

```kotlin
@BeforeEach
재미 setUp() = runSuspendIO {
    ops.dropGraph(graphName) // 표준 패턴: 직접 호출; runSuspendIO 내부에서 일시 중지해도 괜찮습니다.
    서비스.초기화()
    Seed = seedSocialNetwork(service) // 과부하 일시 중지
}

@시험
재미 `getDirectConnections returns 1st degree connections`() = runSuspendIO {
    Val 연결 = 서비스.getDirectConnections(seed.alice.id)
    값 이름 = Connections.map { it.properties[PersonLabel.name.name] }
    이름 shouldContainAll listOf("밥", "프랭크")
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
클래스 SocialNetworkTinkerGraphTest : AbstractSocialNetworkTest() {
    동반 객체 : KLogging()

    대체 val graphName = "test_social_tinkergraph"
    Val ops 재정의: GraphOperations = TinkerGraphOperations()
    val 서비스 재정의 = SocialNetworkService(ops, graphName)

    @AfterAll
    재미있다 tearDown() {
        ops.close()
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
클래스 SocialNetworkSuspendTinkerGraphTest : AbstractSocialNetworkSuspendTest() {
    동반 객체 : KLoggingChannel()

    대체 값 graphName = "test_social_tinkergraph_suspens"
    Val ops 재정의: GraphSuspendOperations = TinkerGraphSuspendOperations()
    val 서비스 재정의 = SocialNetworkSuspendService(ops, graphName)

    @AfterAll
    재미있다 tearDown() {
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
@Tag("통합")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
클래스 Neo4jSocialNetworkTest : AbstractSocialNetworkTest() {
    컴패니언 객체 : KLogging() {
        개인 발 neo4j = Neo4jServer.Launcher.neo4j
    }

    대체 값 graphName = "test_social_neo4j"

    개인용 Val 드라이버: 게으른 {의 드라이버
        GraphDatabase.driver(neo4j.url)
    }

    val ops 재정의: GraphOperations bylazy {
        Neo4jGraphOperations(운전자)
    }

    게으른 {으로 val 서비스를 재정의
        SocialNetworkService(앗, graphName)
    }

    @AfterAll
    재미있다 tearDown() {
        runCatching { 드라이버.닫기() }
            .onFailure { log.warn(it) { "드라이버 종료 실패" } }
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

### T13: TinkerGraph Test Execution + Coverage

- **Complexity**: low
- **Dependencies**: T10
- **Command**: `./gradlew :graph-social-network:test`
- **Expected**: 68 tests pass (34 blocking + 34 suspend — 29 original + 5 new validation cases)
- **Coverage**: Run `./gradlew :graph-social-network:koverHtmlReport` after test; verify ≥ 80% line coverage on `SocialNetworkService` and `SocialNetworkSuspendService`.
- **Common failures**:
  - `neighbors()` returns seed at depth >= 2 — ensure all algorithms filter `{ it.id != personVertexId }`
  - Graph property values are always String — comparison must account for this
  - FOAF candidates must use `distinctBy { it.id }` — depth-2 traversal returns duplicates

### T14: Integration Test Execution

- **Complexity**: medium
- **Dependencies**: T11, T12
- **Command**: `./gradlew :graph-social-network:integrationTest`
- **Expected**: 136 tests pass (34 × 2 integration backends (Neo4j + Memgraph) × 2 service styles)
- **Notes**: Requires Docker. Container startup ~30-60s on first run.

---

## Phase 7 — Documentation

### T15: README Files

- **Complexity**: medium
- **Files**: `graph/social-network/README.md`, `graph/social-network/README.ko.md`
- **Dependencies**: T14 (depend on integration test completion to verify cross-backend behavior before documenting)
- **Structure**: Module title → Architecture → Core Features → Graph Topology → Usage → Build commands → Stack
- **Cross-language navigation (REQUIRED)**:
  - Top of `README.md`: `> [한국어 버전](./README.ko.md)`
  - Top of `README.ko.md`: `> [English version](./README.md)`
- **Diagram**: Embed `docs/images/readme-diagrams/social-network-architecture.png` (generated in T16). Write prose in T15 first; add diagram embed after T16 produces the PNG.

### T16: Architecture Diagram

- **Complexity**: low
- **Files**:
  - `graph/social-network/docs/images/readme-diagrams/social-network-architecture.svg`
  - `graph/social-network/docs/images/readme-diagrams/social-network-architecture.png`
- **Dependencies**: T15
- **Notes**: Invoke `bluetape4k-diagram` skill before generating. Embed only PNG in README.
  After T16 completes, add the diagram embed to README.md and README.ko.md (T15 prose written before T16, diagram embed added after).

---

### T17: Pre-PR Verification (bluetape4k-patterns Checklist)

- **Complexity**: low
- **Dependencies**: T13, T14
- **Commands**:
  ```bash
  ./gradlew :graph-social-network:detekt
  ```
- **Manual checks** (bluetape4k-patterns checklist):
  - [ ] `requireNotBlank` / `requireInRange` / `requirePositiveNumber` used (NOT stdlib `require()`)
  - [ ] `companion object : KLogging()` in blocking services, `KLoggingChannel()` in suspend services
  - [ ] All `data class` declarations implement `Serializable` + `serialVersionUID`
  - [ ] No `runCatching {}` around suspend calls in suspend service
  - [ ] Single companion object per class (logging merged with constants)
  - [ ] `ide_diagnostics` returns zero errors and no unresolved deprecations
  - [ ] `rg "RequireNotBlank\|require(.*isNotBlank" src/` returns 0 hits
  - [ ] English KDoc on all public classes, objects, methods in service and schema files

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

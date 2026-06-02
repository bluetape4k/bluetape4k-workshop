# graph-social-network

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **graph-social-network** 모듈을 실행 가능한 그래프 도메인 모델링 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 흐름 다이어그램

1. `graph-social-network` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

[bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph)를 사용한 LinkedIn 스타일 소셜 네트워크 그래프 예제입니다.

**Person–Company 관계 모델링**, **다중 홉 BFS 탐색**, **FOAF 추천**, **최단 경로 탐색**을 TinkerGraph(인메모리), Neo4j, Memgraph 백엔드로 시연합니다.

---

## 아키텍처

![모듈 아키텍처](docs/images/readme-diagrams/social-network-architecture.png)

```
graph/social-network/
├── src/main/kotlin/
│   └── io/bluetape4k/workshop/graph/social/
│       ├── model/
│       │   └── ConnectionRecommendation.kt   # FOAF 추천 결과 모델
│       ├── schema/
│       │   └── SocialNetworkSchema.kt        # Vertex/Edge 레이블 DSL
│       └── service/
│           ├── SocialNetworkService.kt        # 블로킹 서비스
│           └── SocialNetworkSuspendService.kt # 코루틴/Flow 서비스
└── src/test/kotlin/
    └── io/bluetape4k/workshop/graph/social/
        ├── AbstractSocialNetworkTest.kt         # 34개 블로킹 테스트
        ├── AbstractSocialNetworkSuspendTest.kt  # 34개 suspend 테스트
        ├── SocialNetworkTinkerGraphTest.kt      # 인메모리 TinkerGraph
        ├── SocialNetworkSuspendTinkerGraphTest.kt
        ├── Neo4jSocialNetworkTest.kt            # @Tag("integration")
        ├── Neo4jSocialNetworkSuspendTest.kt
        ├── MemgraphSocialNetworkTest.kt
        └── MemgraphSocialNetworkSuspendTest.kt
```

## 그래프 스키마

![그래프 도메인 모델](docs/images/readme-diagrams/social-network-domain-model.png)

### Vertex 레이블

| 레이블 | ID 프로퍼티 | 기타 프로퍼티 |
|---|---|---|
| `Person` | `personId` | `name`, `title`, `location` |
| `Company` | `companyId` | `name`, `industry`, `location` |

### Edge 레이블

| 레이블 | 방향 | 프로퍼티 |
|---|---|---|
| `KNOWS` | 양방향 (두 개의 방향성 edge) | `since`, `strength` (1–10) |
| `FOLLOWS` | 단방향 | `since` |
| `WORKS_AT` | `Person` → `Company` | `role`, `startDate`, `endDate`, `isCurrent` |

## 시드 토폴로지 (테스트 데이터)

```
alice ──KNOWS──► bob ──KNOWS──► carol ──KNOWS──► dave
                 └───KNOWS──► dave
eve  ──FOLLOWS──► alice

alice ──WORKS_AT──► acme (role="Engineer")
bob   ──WORKS_AT──► acme (role="Designer")
carol ──WORKS_AT──► startup (role="Developer")
```

## 기능

### `SocialNetworkService` (블로킹)

```kotlin
val service = SocialNetworkService(ops, graphName)
service.initialize()

// Vertex 생성 (멱등)
val alice = service.addPerson("alice", "Alice Smith", title = "Engineer", location = "Seoul")
val acme  = service.addCompany("acme", "Acme Corp", industry = "Technology")

// Edge 생성
service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)    // 양방향 KNOWS
service.follow(eve.id, alice.id, since = "2024-06-01")                    // 단방향 FOLLOWS
service.addWorkExperience(alice.id, acme.id, role = "Engineer", isCurrent = true)

// 1촌 연결
val direct: List<GraphVertex> = service.getDirectConnections(alice.id)

// N촌 이내 연결 (1..maxDegree 포함)
val within2: List<GraphVertex> = service.getConnectionsWithinDegree(alice.id, maxDegree = 2)

// 정확히 N촌
val secondDegree: List<GraphVertex> = service.getNthDegreeConnections(alice.id, degree = 2)

// FOAF 추천 (공통인맥 수 내림차순 → personId 오름차순)
val recs: List<ConnectionRecommendation> = service.recommendConnections(alice.id)
// recs[0].person, recs[0].mutualConnectionCount, recs[0].mutualConnections

// 동료 탐색 (같은 회사)
val colleagues: List<GraphVertex> = service.findColleagues(alice.id)

// 최단 경로
val path: GraphPath? = service.findConnectionPath(alice.id, dave.id)
// path.vertices.size == 3  (alice → bob → dave)

// maxDepth 이내 모든 경로
val paths: List<GraphPath> = service.findAllConnectionPaths(alice.id, dave.id, maxDepth = 3)

// 공통 인맥
val mutual: List<GraphVertex> = service.findMutualConnections(alice.id, dave.id)
```

### `SocialNetworkSuspendService` (코루틴 / Flow)

블로킹 서비스와 동일한 API. 스트리밍 결과는 `Flow<T>`, 단일 결과는 `suspend` 함수로 제공:

```kotlin
val service = SocialNetworkSuspendService(ops, graphName)

val direct: Flow<GraphVertex>  = service.getDirectConnections(alice.id)
val paths:  Flow<GraphPath>    = service.findAllConnectionPaths(alice.id, dave.id)
val recs:   List<ConnectionRecommendation> = service.recommendConnections(alice.id)  // suspend
```

## 상수

```kotlin
SocialNetworkService.MAX_TRAVERSAL_DEPTH        // 6
SocialNetworkSuspendService.MAX_TRAVERSAL_DEPTH // 6
```

## 테스트 실행

### 단위 테스트 (TinkerGraph, Docker 불필요)

```bash
./gradlew :graph-social-network:test
```

### 통합 테스트 (Neo4j + Memgraph — Testcontainers)

Docker가 필요합니다.

```bash
./gradlew :graph-social-network:integrationTest
```

## 의존성

```kotlin
// build.gradle.kts
implementation(platform(libs.bluetape4k.graph.bom))
implementation(libs.bluetape4k.graph.core)
implementation(libs.bluetape4k.graph.tinkerpop)

// 통합 테스트 전용
compileOnly(libs.bluetape4k.graph.neo4j)
compileOnly(libs.bluetape4k.graph.memgraph)
```

> **참고:** `bluetape4k-graph`는 `mavenLocal`에서 해석됩니다.
> 먼저 `./gradlew -p bluetape4k-graph publishBluetapeGraphPublicationToMavenLocalRepository`를 실행하세요.

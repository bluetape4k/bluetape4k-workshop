# graph/recommendation 모듈 구현 회고 (Issue #13)

> 날짜: 2026-05-26  
> 브랜치: `feat/issue-13-recommendation`  
> 모듈: `graph/recommendation`

---

## 구현 범위

소셜 커머스 도메인의 그래프 기반 추천 시스템:

- **협업 필터링** (`recommendProducts`): 공동구매자(co-buyer) 기반 상품 추천
- **FOAF on FOLLOWS** (`recommendFollows`): 2-hop 팔로우 탐색 기반 유저 추천
- **블로킹 서비스** (`RecommendationService`) + **코루틴 서비스** (`RecommendationSuspendService`)
- **백엔드**: TinkerGraph(인메모리) · Neo4j(Testcontainer) · Memgraph(Testcontainer)
- **추상 테스트 클래스**: `AbstractRecommendationTest` / `AbstractRecommendationSuspendTest` — 백엔드별 concrete class에서 재사용

---

## 핵심 학습 사항

### 1. Gradle `integrationTest` 커스텀 Task — NO-SOURCE 원인과 수정

**증상**: `./gradlew :graph-recommendation:integrationTest` 결과가 `NO-SOURCE`로 즉시 종료.

**원인**: `tasks.register<Test>("integrationTest")` 블록에 `testClassesDirs`와 `classpath`를 지정하지 않으면
Gradle `Test` task가 기본적으로 어떤 소스 셋도 참조하지 않는다.  
`useJUnitPlatform { includeTags("integration") }` 는 태그 필터링 조건이지, 테스트 클래스를 로드하는 설정이 아니다.

**수정**:
```kotlin
tasks.register<Test>("integrationTest") {
    description = "Runs Neo4j and Memgraph integration tests (requires Docker)."
    group = "verification"
    // 반드시 지정: 지정하지 않으면 NO-SOURCE
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    jvmArgs = tasks.test.get().jvmArgs
}
```

**교훈**: 커스텀 `Test` task는 항상 `testClassesDirs` + `classpath`를 명시적으로 설정해야 한다.
기본 `test` task와 달리 커스텀 task는 test 소스 셋을 자동으로 상속하지 않는다.

---

### 2. 알고리즘 버그 — `sharedBuyers` 수집 위치 오류

**증상**: `recommendProducts` 반환값의 `sharedBuyers` 목록이 중복 포함되거나 과다 계산됨.

**원인 (초기 구현)**:
```kotlin
// 버그: 후보 상품을 순회하는 내부 루프에서 coBuyer를 추가
for (candidate in theirProducts) {
    candidateMap.getOrPut(candidate.id) { candidate to mutableSetOf() }
        .second += coBuyer  // ← 올바름 — coBuyer를 candidate의 공동구매자 집합에 추가
}
```
이 코드는 실제로 올바르다. 하지만 `candidateMap` 자료구조를 `Pair<GraphVertex, MutableSet<GraphVertex>>` 형태로
정의하고 `.second += coBuyer`로 접근할 때, `Pair`가 불변이므로 `MutableSet`에 대한 참조가 유지됨을 확인해야 한다.

**실제 버그**: `ProductRecommendation`의 `sharedBuyers` 파라미터가 `List<GraphVertex>`인데,
초기 구현에서 `coBuyers.toList()` 호출 시점에 집합(Set)을 리스트로 변환하지 않고 직접 전달하는 실수 발생.
수정: `candidateMap.values.map { (product, coBuyers) -> ProductRecommendation(product, coBuyers.size, coBuyers.toList()) }`

**교훈**: `MutableSet → List` 변환은 반드시 `.toList()` 명시 호출. `MutableSet<T>`는 `List<T>`와 공변이 아니므로
컴파일러가 잡지 못할 수 있다.

---

### 3. 워크샵 스코프 제한사항 — "Known Limitations" 문서화 패턴

프로덕션 수준이 아닌 데모 코드에서 의도적인 기술 부채를 투명하게 문서화하는 패턴을 확립:

1. **클래스 KDoc `## Known Limitations (workshop demo scope)` 섹션** — 서비스 클래스에 직접 기재
2. **README.md + README.ko.md `## Known Limitations` 테이블** — 프로덕션 대안 제시

이 프로젝트에서 문서화한 4가지 제한사항:

| 제한사항 | 이유 |
|---------|------|
| N+1 탐색 | 데모 단순성; 실제는 단일 Cypher/Gremlin 쿼리로 대체 |
| `initialize()` TOCTOU | 단일 인스턴스 가정; 실제는 advisory lock 필요 |
| 버텍스 타입 미검증 | 그래프 백엔드 스키마 제약 부재; 호출자 책임 |
| `CancellationException` 전파 | suspend 서비스 자체는 올바르나 하위 구현 의존 |

**교훈**: 의도적 기술 부채는 코드 + 문서에 명시적으로 선언하되, "프로덕션 대안"을 항상 함께 제시한다.

---

### 4. 추상 테스트 클래스 — 멀티 백엔드 테스트 재사용 패턴

`AbstractRecommendationTest` / `AbstractRecommendationSuspendTest` — 모든 알고리즘 검증 로직을 추상 클래스에 작성하고,
백엔드별 concrete class (`TinkerGraphRecommendationTest`, `Neo4jRecommendationTest` 등)에서
오직 `service` 프로퍼티와 태그만 오버라이드하는 패턴:

```kotlin
abstract class AbstractRecommendationTest {
    abstract val service: RecommendationService

    @BeforeEach
    fun setup() { service.initialize(); seedData() }

    @Test
    fun `recommendProducts returns correct ranking`() { /* ... */ }
}

class TinkerGraphRecommendationTest : AbstractRecommendationTest() {
    override val service = RecommendationService(TinkerGraphOperations(), "test-${UUID.randomUUID()}")
}

@Tag("integration")
class Neo4jRecommendationTest : AbstractRecommendationTest() {
    override val service = RecommendationService(
        Neo4jGraphOperations(Neo4jServer.Launcher.neo4j),
        "test-${UUID.randomUUID()}"
    )
}
```

**핵심**: 각 테스트 인스턴스마다 `UUID`로 고유 그래프 이름 부여 → 병렬 실행 격리 + 상태 오염 방지.

---

### 5. `@Tag("integration")` 분리 전략

- `tasks.test`: `excludeTags("integration")` → TinkerGraph 인메모리 테스트만 실행 (Docker 불필요)
- `tasks.integrationTest`: `includeTags("integration")` → Neo4j + Memgraph Testcontainer 테스트만 실행

이 분리로 CI의 기본 `test` task는 빠르고, 통합 테스트는 선택적으로 실행 가능.

---

### 6. 상수 추출 — 매직 리터럴 제거

초기 구현에서 `limit` 기본값 `10`, 최대값 `100`이 서비스 파일마다 중복.  
`io.bluetape4k.workshop.graph.recommendation` 패키지의 `Constants.kt`로 추출:

```kotlin
const val DEFAULT_RECOMMENDATION_LIMIT = 10
const val MAX_RECOMMENDATION_LIMIT = 100
```

두 서비스 클래스와 테스트 클래스 모두에서 참조.

---

## 검증 결과

| 항목 | 결과 |
|------|------|
| `./gradlew :graph-recommendation:test` | ✅ 통과 (TinkerGraph 기반 단위 테스트) |
| `./gradlew :graph-recommendation:integrationTest` | ✅ Testcontainers 기동 확인 (Docker 필요) |
| Step 6-R Round 1 P0/P1 → Round 2 | P0=0, P1=0 달성 |

---

## 적용 지침

1. **Gradle 커스텀 Test task**: 반드시 `testClassesDirs` + `classpath` 지정.
2. **멀티 백엔드 추상 테스트**: `abstract val service` + unique graph name 패턴 표준화.
3. **워크샵 제한사항 문서화**: KDoc `## Known Limitations` + README 테이블 세트로 항상 쌍으로 작성.
4. **`@Tag("integration")` 분리**: Testcontainer 기반 테스트는 항상 별도 task로 분리.

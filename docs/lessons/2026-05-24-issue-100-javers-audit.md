# 2026-05-24 Issue #100 — exposed/javers-audit 구현 교훈

## 개요

JaVers를 활용한 엔티티 변경 이력 추적 워크샵 모듈 (`exposed/javers-audit`) 구현.

## 주요 결정

### API 탐색 먼저

작업 지시서에 API 추측이 포함되어 있었음. 실제 소스를 먼저 읽어서 확인:

- `JaversExtensions.kt` → `latestSnapshotOrNull<T>(id)` 확인
- `DiffExtensions.kt` → `diff.changesByType<ValueChange>()` — reified inline 확장
- `CdoSnapshot.type` (not `.snapshotType`) — `SnapshotType` enum
- `SnapshotType` import: `org.javers.core.metamodel.\`object\`.SnapshotType`

### 의존성 alias 확인

Workshop `libs.versions.toml`에 javers 항목 없음 → `chore/catalog-javers-text` 브랜치에 이미 추가됨. `issue-100-javers-audit` 워크트리가 동일한 카탈로그를 사용하므로 `libs.bluetape4k.javers.core` 사용 가능.

JaVers direct dependency (`org.javers:javers-core`)는 `bluetape4k-javers-core`의 transitive로 제공됨 — 별도 alias 불필요.

### 테스트 assertion 패키지

`org.amshove.kluent` 가 아닌 `io.bluetape4k.assertions` 사용:

```kotlin
// 잘못된 것 (kluent — bluetape4k-assertions에 없음)
import org.amshove.kluent.shouldBeEqualTo

// 올바른 것
import io.bluetape4k.assertions.shouldBeEqualTo
```

`shouldBeEqualTo`, `shouldHaveSize`는 infix, `shouldBeTrue()` / `shouldBeFalse()` / `shouldBeNull()` / `shouldNotBeNull()`은 일반 함수.

`shouldNotBeNull()`은 kotlin contract로 smart-cast를 지원하므로 이후 `!!` 없이 사용 가능.

### 로깅 람다 import

`KLogging`의 `log`는 `org.slf4j.Logger`. 람다 형식 `log.debug { }` 사용 시 명시적 import 필요:

```kotlin
import io.bluetape4k.logging.debug
```

없으면 Kotlin 컴파일러가 SLF4J의 `debug(String)` 오버로드로 해석해서 타입 오류 발생.

### Exposed v1 import 경로

```kotlin
import org.jetbrains.exposed.v1.core.eq          // eq 연산자
import org.jetbrains.exposed.v1.jdbc.deleteWhere  // deleteWhere
import org.jetbrains.exposed.v1.jdbc.upsert       // upsert
import org.jetbrains.exposed.v1.jdbc.SchemaUtils  // SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
```

`SqlExpressionBuilder.eq` 는 deprecated — 최상위 `eq` import 사용.

## 완료 조건 달성

- 8개 테스트 모두 통과 (failures=0, errors=0)
- detekt: 0 issues
- Spring/Testcontainers 없이 순수 JaVers + Exposed + H2 인메모리

# Issue #862 Testcontainers Spring bridge 적용 설계

## 문제

`shared`의 `RedisTestSupport`는 `DynamicPropertyRegistry`에 Redis endpoint를
수동으로 등록한다. `bluetape4k-dependencies:2.0.0`에는
`io.github.bluetape4k:bluetape4k-testcontainers-spring` 선택 모듈이 포함되어
있으며, upstream은 `PropertyExportingServer.registerDynamicProperties`로
동일한 등록 계약을 제공한다. 기존 Redis 예제를 이 계약으로 갱신해
2.0.0의 개선 기능을 실제 사용 코드에서 보여 주고, Docker 없는 계약 검증과
실제 Redis 회귀 검증의 경계를 분명히 한다.

## 근거와 현재 상태

- 저장소 기준: `gradle/libs.versions.toml`의
  `bluetape4k-dependencies-version = "2.0.0"`.
- 현재 helper: `shared/src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt`가
  `testcontainers.redis.host`, `.port`, `.url`을 직접 등록한다.
- 현재 소비자: `spring-data/redis-examples`의 동기·reactive
  `@DynamicPropertySource`가 `RedisTestSupport.registerRedisProperties`를 호출한다.
- upstream bridge 구현: [bluetape4k-projects PR #1454](https://github.com/bluetape4k/bluetape4k-projects/pull/1454)
  및 `PropertyExportingServerDynamicPropertyRegistry.kt`의 실제 구현.
- upstream 사용 예: [bluetape4k-exposed PR #717](https://github.com/bluetape4k/bluetape4k-exposed/pull/717)가
  PostgreSQL benchmark에서 `postgres.registerDynamicProperties(registry)`를
  사용한다.

## 선택지

### A. helper를 upstream bridge에 위임 (채택)

`RedisTestSupport.registerRedisProperties`가
`redis.registerDynamicProperties(registry)`를 호출한다. `shared`에는 bridge를
compile-only로 연결하고 테스트 runtime에만 추가하며, 실제 Spring Redis 소비
모듈에는 `testImplementation`으로 bridge를 명시한다. fake server와 recording
registry로 bridge 계약을 Docker 없이 검증하고, Redis endpoint 회귀는
container 테스트로 남긴다.

- 장점: 기존 호출자는 그대로 두고 실제 예제가 2.0.0 API를 사용한다.
- 장점: key/lazy/예외/중복 등록 semantics를 upstream에 위임해 중복 구현을
  제거한다.
- 단점: bridge를 호출하는 소비자 test runtime에 선택 의존성을 추가해야 한다.

### B. 수동 등록 유지 + bridge 비교 예제만 추가

기존 helper는 유지하고 별도 fake 예제로 bridge의 동작만 보여 준다.

- 장점: runtime 의존성 변경이 없다.
- 단점: 대표 Redis 예제가 새 기능을 실제 적용하지 않아 이슈 목적이 약하다.

### C. 모든 Redis/Spring 소비자 일괄 migration

모든 `RedisTestSupport` 호출 모듈에 bridge 의존성과 API 변경을 적용한다.

- 장점: 저장소 전체의 사용 방식이 통일된다.
- 단점: Issue #862가 제외한 일괄 migration이며, 비-Spring·container 경계를
  불필요하게 넓힌다.

## 채택 설계

### 의존성 경계

- `gradle/libs.versions.toml`에
  `bluetape4k-testcontainers-spring = { module =
  "io.github.bluetape4k:bluetape4k-testcontainers-spring" }`를 versionless로
  추가한다.
- `shared/build.gradle.kts`에는 bridge를 `compileOnly`와
  `testImplementation`으로 선언한다. shared의 일반 소비자에게 Spring bridge가
  자동 전파되지 않도록 `implementation`은 사용하지 않는다.
- `spring-data/redis-examples/build.gradle.kts`에는 bridge를
  `testImplementation`으로 추가한다. Spring Test와 Testcontainers 자체
  의존성은 해당 소비자가 계속 소유한다.
- BOM은 유일한 Bluetape4k 버전 원본이며, 개별 alias에 버전을 고정하지 않는다.

### 코드와 테스트

1. `RedisTestSupport.registerRedisProperties`의 수동 `registry.add` 세 줄을
   upstream extension 호출로 대체한다. `RedisServer.Launcher.redis` singleton,
   endpoint key, start/stop 소유권은 변경하지 않는다.
2. `PropertyExportingServerDynamicPropertyRegistryTest`를 추가한다. fake
   `PropertyExportingServer`와 recording registry로 다음을 Docker 없이 검증한다.
   - `propertyKeys()`의 full key mapping과 빈 key no-op
   - 등록 시 supplier 비평가와 반복 평가 시 최신 값 조회
   - 누락 key의 `IllegalStateException`
   - `properties()` 예외의 타입·메시지 전달
   - 중복 등록을 registry 순서에 위임
   - system property를 변경하지 않음
3. 기존 `RedisTestSupportTest`는 실제 Redis endpoint와 singleton lifecycle을
   검증하는 container 경로로 유지한다. `RedisServer.Launcher.redis` 접근으로
   singleton을 확보하는 동작은 유지하되, container start/stop을 bridge가
   호출하지 않는다는 경계는 fake contract 테스트로 분리해 확인한다.
4. smoke workflow에는 Docker 없는 bridge contract test만 선택 실행하고,
   container workflow에는 실제 Redis helper 회귀 테스트를 선택 실행한다.
   두 경로를 한 Gradle test invocation으로 섞지 않는다.

### README/KDoc

`shared/README.md`, `shared/README.ko.md`, helper KDoc에 다음을 반영한다.

- `registerDynamicProperties` 사용 예와 `testcontainers.redis.*` key 목록
- bridge가 supplier 평가만 연결하며 container start/stop, readiness, JVM
  system property를 소유하지 않는다는 lifecycle 경계
- `shared` compile-only와 Redis 소비자 test runtime dependency ownership
- Docker-free contract test와 Docker-required endpoint test의 실행 구분

영문·한국어 README는 구조, 코드, key, 명령, 경계 설명을 동등하게 유지한다.

## 실패 모드와 대응

| 실패 모드 | 감지 방법 | 대응 |
|---|---|---|
| bridge alias가 BOM에서 해석되지 않음 | `dependencyInsight`에서 `2.0.0` 선택 실패 | versionless alias와 root BOM import를 확인하고 개별 버전 고정을 거부한다. |
| supplier가 등록 시 평가되어 Docker가 없는 smoke가 실패함 | fake registry의 `propertiesCalls`가 0이 아님 | registry가 supplier를 보관하도록 bridge contract를 고정하고, Docker 없는 테스트는 fake server만 사용한다. |
| bridge 누락으로 Redis consumer runtime에서 `NoClassDefFoundError` 발생 | Redis example targeted test 실패 | 해당 소비자에만 `testImplementation` bridge를 추가하고 dependency insight로 runtime classpath를 확인한다. |
| endpoint key 또는 값이 바뀌어 Spring context가 뜨지 않음 | Redis helper 회귀 테스트와 context test 실패 | `RedisServer.propertyKeys/properties` 계약과 기존 key를 비교하고 변경을 되돌린다. |
| bridge가 system property/lifecycle을 오염시킴 | fake contract 및 before/after system property 검사 | bridge가 registry만 호출하는지 확인하고 start/stop 코드를 추가하지 않는다. |

## 호환성과 범위

- 기존 `RedisTestSupport.registerRedisProperties(registry)` 호출 시그니처와
  세 가지 property key를 유지한다.
- bridge 자체 구현, Spring Boot auto-configuration, container 자동
  start/stop, 모든 workshop 모듈 migration은 범위에서 제외한다.
- non-Spring 테스트에는 bridge를 추가하지 않으며, shared 의존성을 사용하지
  않는 모듈의 build graph는 변경하지 않는다.
- 실패 시 alias·build 선언과 helper 호출을 함께 되돌릴 수 있고, 기존 수동
  등록으로 복귀하면 endpoint contract는 보존된다.

## 수용 기준과 DoD

- root BOM만으로 versionless bridge alias가 `2.0.0`으로 해석된다.
- 기존 Redis key와 singleton lifecycle이 유지되고, helper 회귀 테스트가
  통과한다.
- Docker 없는 contract test가 lazy/예외/중복/system property 경계를 증명한다.
- smoke와 container workflow가 각 테스트 경로를 분리한다.
- 한·영 README와 KDoc이 실제 API·의존성 경계를 설명한다.
- `./gradlew :shared:test` 및
  `./gradlew :spring-data-redis-examples:test`의 필요한 targeted test,
  dependency insight, `git diff --check`가 통과한다.
- PR 제목·본문과 Issue #862의 milestone/labels 정책을 `2.0.0`으로 유지한다.

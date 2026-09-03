# Issue #862 Testcontainers Spring bridge 적용 구현 계획

> Issue: [#862](https://github.com/bluetape4k/bluetape4k-workshop/issues/862)
>
> Spec: `docs/superpowers/specs/2026-09-03-issue-862-testcontainers-spring-bridge-design.md`
>
> 기준: `origin/develop` `c9fb5b9d`, `bluetape4k-dependencies:2.0.0`

## 목표와 불변식

- `shared`의 Redis helper가 `PropertyExportingServer.registerDynamicProperties`
  upstream extension을 사용하도록 바꿔 실제 2.0.0 기능을 기존 소비자 예제에
  적용한다.
- `bluetape4k-dependencies` BOM만 사용하고 개별 Bluetape4k 버전이나
  snapshot 좌표를 추가하지 않는다.
- `RedisTestSupport.registerRedisProperties(registry)`의 공개 시그니처와
  `testcontainers.redis.host/port/url` key, `RedisServer.Launcher.redis`
  singleton 및 start/stop 소유권은 유지한다.
- bridge는 `DynamicPropertyRegistry`에 supplier만 등록한다. container
  lifecycle, readiness, JVM system property 기록은 bridge 경로에 추가하지
  않는다.
- `RedisTestSupport.redis` singleton 접근은 기존처럼 container 시작을
  유발할 수 있다. Docker 없는 검증은 helper를 참조하지 않는 fake server와
  최소 Spring context 테스트로만 실행한다.
- 기존의 unrelated worktree·branch와 root `develop` 작업 트리는 건드리지
  않는다.

## TDD 작업 순서

### 1. 의존성 계약을 먼저 고정한다

대상 파일:

- `gradle/libs.versions.toml`
- `shared/build.gradle.kts`
- `spring-data/redis-examples/build.gradle.kts`

작업:

1. `bluetape4k-testcontainers-spring` versionless alias를 catalog에 추가한다.
2. `shared`에 `compileOnly`와 test runtime용
   `testImplementation`을 추가한다. 일반 `implementation`으로 Spring
   bridge를 전파하지 않는다.
3. Redis 소비자 모듈에 `testImplementation` bridge를 명시한다. Spring
   Test/Testcontainers 자체 dependency ownership은 소비자에 남긴다.

검증:

- `:shared:dependencyInsight`와
  `:spring-data-redis-examples:dependencyInsight`에서 bridge가
  `2.0.0` release로 선택되는지 확인한다.
- catalog와 build 파일에 snapshot 또는 개별 Bluetape4k 버전이 없는지
  검색한다.

### 2. Docker-free bridge contract를 RED→GREEN으로 만든다

대상 파일:

- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/PropertyExportingServerDynamicPropertyRegistryTest.kt`
- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/PropertyExportingServerDynamicPropertyRegistryContextTest.kt`

RED 단계:

1. fake `PropertyExportingServer`와 supplier를 평가하지 않고 보관하는
   recording `DynamicPropertyRegistry`를 작성한다.
2. upstream 계약을 고정하는 테스트를 먼저 작성한다.
   - namespace가 포함된 full key mapping과 empty key no-op
   - 등록 직후 `properties()` 호출 0회 및 key set 순서 무관성
   - supplier 평가 때 최신 값 조회, key별 반복 `properties()` 호출
   - 누락 key의 `IllegalStateException`
   - `properties()` 원래 예외 타입·메시지 전달
   - duplicate registration을 recording registry에 위임
   - 등록 전후 JVM system property 불변
3. `@DynamicPropertySource`와 최소 `@SpringJUnitConfig` context를 사용해
   fake server 값이 실제 Spring `Environment`/property binding에 노출되는지
   검증한다. 이 테스트는 `RedisTestSupport`나 Docker singleton을 참조하지
   않는다.

GREEN 단계:

- 테스트가 compile/run되도록 bridge dependency와 production delegation을
  반영한다. 테스트는 property 등록 순서를 assert하지 않고 key set과
  invocation semantics만 assert한다.

실행 경계:

- smoke: `./gradlew :shared:test --tests '*PropertyExportingServerDynamicPropertyRegistryTest'`
- smoke: `./gradlew :shared:test --tests '*PropertyExportingServerDynamicPropertyRegistryContextTest'`
- 두 selector는 Docker-free이며 하나의 Gradle invocation에 합치지 않는다.

### 3. Redis helper를 upstream extension으로 전환한다

대상 파일:

- `shared/src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt`

작업:

1. `io.bluetape4k.testcontainers.spring.registerDynamicProperties`를 import한다.
2. 수동 `registry.add` 세 줄을
   `redis.registerDynamicProperties(registry)` 한 줄로 대체한다.
3. helper 공개 API와 singleton 초기화, endpoint key를 변경하지 않는다.

검증:

- contract/context 테스트가 통과한다.
- source에 bridge가 `registerSystemProperties` 또는 container lifecycle을
  호출하는 코드가 없는지 확인한다.

### 4. 기존 Redis 회귀를 container 경로에서 확인한다

대상 파일:

- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupportTest.kt`
- `spring-data/redis-examples/src/test/kotlin/**/AbstractRedisTest.kt`
- `spring-data/redis-examples/src/test/kotlin/**/reactive/AbstractReactiveRedisTest.kt`

작업:

- 기존 endpoint key와 실제 host/port/url 값을 계속 검증한다.
- 동기·reactive 소비자 callback이 helper를 통해 동일한 key를 등록하는지
  기존 테스트로 확인한다. 불필요한 all-module migration은 하지 않는다.

실행 경계:

- container: `./gradlew :shared:test --tests '*RedisTestSupportTest'`
- container: 기존 `:spring-data-redis-examples:test`의 필요한 targeted
  Redis 테스트를 `--max-workers=1`로 실행한다.

### 5. README/KDoc와 workflow 실행 경로를 동기화한다

대상 파일:

- `shared/README.md`
- `shared/README.ko.md`
- `shared/src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt`
- `.github/workflows/Examples.yml`
- `scripts/smoke-validate.sh`

작업:

- 두 README에 동일한 bridge 사용 예, key 목록, dependency ownership,
  supplier/lifecycle/system-property 경계, helper의 Docker-required 제한을
  반영한다.
- KDoc은 기존 helper 호출자가 이해할 수 있게 upstream delegation과
  singleton 시작 가능성을 명시한다.
- Examples smoke job에 두 Docker-free `:shared:test --tests` invocation을
  추가하고, container job에 `:shared:test --tests '*RedisTestSupportTest'`
  및 Redis consumer targeted 경로를 추가한다.
- `scripts/smoke-validate.sh`의 smoke와 `data-access-full` 경로에도 같은
  selector를 넣는다. 전체 `:shared:test`를 smoke에서 호출하지 않는다.
- test report artifact 경로에 root `shared` 결과가 누락되지 않도록 필요한
  경로만 추가한다.

### 6. 통합 검증과 lesson을 남긴다

검증 순서:

1. `git diff --check`와 untracked 파일 `git diff --no-index --check`.
2. Docker-free contract/context targeted tests.
3. `dependencyInsight`에서 bridge/BOM `2.0.0` 선택 확인.
4. Colima/Docker 상태를 확인한 뒤 container Redis helper 및 Redis consumer
   targeted tests를 `--max-workers=1`로 실행한다.
5. `bash -n scripts/smoke-validate.sh`, YAML 변경 확인, snapshot 검색,
   README EN/KO 구조 비교.
6. 필요한 경우 `./gradlew :shared:test` 전체가 아닌 위 selector 조합으로
   smoke/container 경계를 재현한다.

결과 기록:

- `docs/lessons/2026-09-03-issue-862-testcontainers-spring-bridge.md`에
  실제 테스트 명령·결과, helper singleton lifecycle 제한, upstream 위임
  범위, 남은 위험을 한국어로 기록한다.

## 실패 시 복구

- bridge alias 해석 실패 시 build 선언과 alias를 함께 되돌리고 root BOM
  import만 남긴다.
- contract/context 테스트 실패 시 production delegation을 되돌린 뒤
  기존 수동 endpoint 등록으로 복귀할 수 있다.
- Docker/Redis 회귀 실패 시 smoke selector는 유지하고 container 경로만
  재진단한다. container lifecycle을 helper 또는 bridge에 새로 추가하지
  않는다.
- workflow 변경은 정확한 selector와 artifact 경로만 되돌릴 수 있도록
  독립된 작은 diff로 유지한다.

## 완료 기준

- 모든 변경은 `2.0.0` 안정 BOM으로 해석되고 snapshot dependency가 없다.
- fake contract와 최소 Spring context 테스트가 Docker 없이 통과한다.
- Redis helper와 소비자 회귀 테스트가 container 경로에서 통과하고 기존
  endpoint/lifecycle이 보존된다.
- smoke/container workflow 및 `scripts/smoke-validate.sh`가 정확한
  selector로 분리되어 실행된다.
- EN/KO README와 KDoc이 동일한 API·경계·의존성 정보를 설명한다.
- lesson, diff check, targeted test, dependency insight, script/YAML
  검증 결과를 PR 본문 DoD에 기록한다.

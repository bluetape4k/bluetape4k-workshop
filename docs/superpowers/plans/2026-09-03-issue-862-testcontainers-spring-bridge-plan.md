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

## Step 3-R 계획 검토 보정

- upstream characterization test가 production helper 변경 전에도 통과할
  수 있다는 점을 명시하고, delegation 전 baseline·post-refactor Redis
  회귀 결과를 나란히 기록한다.
- Docker-free 세 contract/context/source selector는 한 번의 독립된 `:shared:test`
  invocation으로 실행한다. 여러 module task에 공통 `--tests`를 붙이거나
  selector별로 같은 test report를 덮어쓰는 실행은 하지 않는다.
- affected module 전체 증명은 Docker-capable sequential lane의 full
  `:shared:test`와 full `:spring-data-redis-examples:test`로 수행하고,
  smoke에는 Docker-free selector만 둔다.
- workflow 수정 후 `actionlint`와 인접 `env:`/command read-back을 필수로
  수행한다. helper 호출자는 abstract base뿐 아니라 stream test까지 full
  consumer test로 검증한다.

## TDD 작업 순서

새 fake contract/context 테스트는 upstream extension의 characterization
test다. upstream 구현이 이미 2.0.0에 존재하므로 production helper를 바꾸기
전에도 이 테스트 자체는 GREEN일 수 있다. 이를 합성 RED로 주장하지 않고,
기존 `RedisTestSupportTest`의 delegation 전 baseline과 동일 테스트의
post-refactor 결과를 나란히 기록해 helper 변경의 회귀를 증명한다. dependency
미존재에 따른 compile failure는 RED 증거로 사용하지 않는다.

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

- `./gradlew :shared:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-testcontainers-spring --configuration testRuntimeClasspath`
  및 `./gradlew :spring-data-redis-examples:dependencyInsight --dependency io.github.bluetape4k:bluetape4k-testcontainers-spring --configuration testRuntimeClasspath`에서
  bridge가 `2.0.0` release로 선택되는지 확인한다.
- catalog와 build 파일에 snapshot 또는 개별 Bluetape4k 버전이 없는지
  검색한다.

### 2. Docker-free bridge contract를 RED→GREEN으로 만든다

대상 파일:

- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/PropertyExportingServerDynamicPropertyRegistryTest.kt`
- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/PropertyExportingServerDynamicPropertyRegistryContextTest.kt`
- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupportBridgeContractTest.kt`

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
3. repository source contract test가 `RedisTestSupport`의 실제 위임 호출을
   판정한다. source에 `redis.registerDynamicProperties(registry)`가 존재하고
   수동 `registry.add`가 제거되었는지 확인해 characterization test가 수동
   구현만으로 GREEN이 되지 않도록 한다. 이 검사가 production delegation
   전에는 RED, 전환 후에는 GREEN이 되는 유일한 helper-specific RED 증거다.
4. `@DynamicPropertySource`와 최소 `@SpringJUnitConfig` context를 사용해
   fake server 값이 실제 Spring `Environment`/property binding에 노출되는지
   검증한다. 이 테스트는 `RedisTestSupport`나 Docker singleton을 참조하지
   않는다. 구체적으로 `@SpringJUnitConfig(classes = [TestConfig::class])`와
   `@Configuration(proxyBeanMethods = false)`인 nested `TestConfig`를 두고,
   `@Bean`으로 fake server와 `@ConfigurationProperties(prefix =
   "testcontainers.redis")` bound endpoint를 등록한다. companion object의
   `@JvmStatic @DynamicPropertySource`는 동일 fake server를 bridge에 위임하며,
   component scan이나 application context auto-configuration은 사용하지 않는다.

Baseline 경계:

- production delegation을 바꾸기 전에 기존
  `./gradlew :shared:test --tests '*RedisTestSupportTest' --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain`
  을 실행하고, 명령·exit code·요약을 lesson의 `baseline` 항목으로 기록한다.
- helper 위임 변경과 dependency/catalog 변경을 끝낸 뒤 동일 명령을 다시
  실행해 `post-refactor` 항목에 기록한다. baseline과 post-refactor 결과가
  있어야 helper 회귀의 GREEN을 주장할 수 있으며, characterization test의
  선행 GREEN을 RED 증거로 오인하지 않는다.

Source guard 경계:

- `RedisTestSupportBridgeContractTest`는 `shared/src/main/kotlin` 기준의
  고정된 상대 경로를 사용하고, `Paths.get("shared/src/main/kotlin/.../
  RedisTestSupport.kt")`가 없으면 `projectDir`/현재 working directory를
  기준으로 한 번만 보정한다. 해석된 경로와 `exists()`를 assertion failure에
  포함해 subproject 실행 위치 변화가 조기에 드러나도록 한다.

GREEN 단계:

- 테스트가 compile/run되도록 bridge dependency와 production delegation을
  반영한다. 테스트는 property 등록 순서를 assert하지 않고 key set과
  invocation semantics만 assert한다.

실행 경계:

- smoke: 하나의 isolated invocation에서 세 selector를 함께 실행한다.
  `./gradlew :shared:test --tests '*PropertyExportingServerDynamicPropertyRegistryTest' --tests '*PropertyExportingServerDynamicPropertyRegistryContextTest' --tests '*RedisTestSupportBridgeContractTest'`
- 세 selector는 Docker-free이며, 다른 Gradle test task와 섞지 않는다. 한
  invocation으로 실행해 test report가 서로 덮어쓰이지 않도록 한다.

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
- 새 logging/reporting 코드가 endpoint 값이나 credential을 출력하지 않는지
  확인한다.

### 4. 기존 Redis 회귀를 container 경로에서 확인한다

대상 파일:

- `shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupportTest.kt`
- `spring-data/redis-examples/src/test/kotlin/**/AbstractRedisTest.kt`
- `spring-data/redis-examples/src/test/kotlin/**/reactive/AbstractReactiveRedisTest.kt`

작업:

- 기존 endpoint key와 실제 host/port/url 값을 계속 검증한다.
- `RedisTestSupport.redis === RedisServer.Launcher.redis` identity를
  검증해 singleton 재생성이나 lifecycle ownership 변경을 방지한다.
- 동기·reactive 소비자 callback이 helper를 통해 동일한 key를 등록하는지
  기존 테스트로 확인한다. 불필요한 all-module migration은 하지 않는다.

실행 경계:

- container: `./gradlew :shared:test --tests '*RedisTestSupportTest'`
- container: 위 helper targeted test와 full module proof는 서로 다른
  invocation으로 실행해 test report와 실패 원인을 분리한다.
- container: `./gradlew :shared:test --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain`로
  affected shared module 전체 테스트를 순차 실행한다. 이 경로에는 기존
  Redis·HTTP helper가 요구하는 Docker lifecycle과 `TestMutexService`를
  포함한다.
- container: `./gradlew :spring-data-redis-examples:test --max-workers=1`
  전체를 실행해 `AbstractRedisTest`, `AbstractReactiveRedisTest`,
  `SyncStreamApiTest`, `ReactiveStreamApiTest`를 포함한 모든 직접·간접
  helper 호출자를 검증한다.
- 위의 모든 container invocation에는 기존과 동일한
  `TESTCONTAINERS_RYUK_DISABLED=true`와
  `DOCKER_HOST=unix:///var/run/docker.sock`를 job-level 또는 step-level
  환경으로 명시한다. 새 step이 기존 step-local 환경을 암묵적으로 상속한다고
  가정하지 않는다.

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
- Examples smoke job에 세 Docker-free selector를 하나의 isolated
  `:shared:test` invocation으로 추가하고, container job에
  `:shared:test --tests '*RedisTestSupportTest'`, `:shared:test` 전체
  sequential proof, `:spring-data-redis-examples:test` 전체를 별도
  invocation으로 추가한다.
- `scripts/smoke-validate.sh`의 smoke와 `data-access-full` 경로에도 같은
  selector를 넣는다. `all-smoke`에는 Docker-free combined selector만,
  `data-access-full`에는 shared 전체와 Redis consumer 전체를 순차 실행한다.
- test report artifact 경로에 root `shared` 결과가 누락되지 않도록 필요한
  경로만 추가한다.
- workflow YAML 수정 뒤 `actionlint .github/workflows/Examples.yml`를
  실행하고, 변경한 `env:` block과 step command를 read-back한다.

### 6. 통합 검증과 lesson을 남긴다

검증 순서:

1. `git diff --check`와 untracked 파일 `git diff --no-index --check`.
2. Docker-free contract/context combined-selector test.
3. `dependencyInsight`에서 bridge/BOM `2.0.0` 선택 확인.
4. Colima/Docker 상태를 확인한 뒤 container Redis helper targeted test,
   full `:shared:test`, full `:spring-data-redis-examples:test`를 각각
   `--max-workers=1`로 실행한다.
5. `bash -n scripts/smoke-validate.sh`, `actionlint
   .github/workflows/Examples.yml`, 변경 `env:`/command read-back, snapshot
   검색, README EN/KO 구조 비교.
6. smoke에서는 combined Docker-free selector만, container에서는 full
   affected-module proof까지 재현한다.

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

# Issue #862 Testcontainers Spring bridge 적용 lesson

## 결정

`bluetape4k-dependencies:2.0.0`이 관리하는
`bluetape4k-testcontainers-spring`을 versionless alias로 연결하고,
`shared`의 `RedisTestSupport.registerRedisProperties`가
`PropertyExportingServer.registerDynamicProperties`에 위임하도록 변경했다.
기존 공개 helper 시그니처, `testcontainers.redis.host/port/url` key,
`RedisServer.Launcher.redis` singleton과 container lifecycle ownership은
유지했다.

bridge는 `propertyKeys()`만 등록 시점에 읽고 각 supplier 평가 때 최신
`properties()` map을 조회한다. 따라서 등록 자체는 container start/stop,
readiness 대기, JVM system property 변경을 수행하지 않는다. 다만
`RedisTestSupport.redis` singleton에 접근하는 기존 동작은 Redis container를
시작할 수 있으므로, fake server contract/context 검증과 실제 Redis 회귀를
분리했다.

## 검토에서 반영한 경계

- upstream characterization test는 production helper 변경 전에도 GREEN일 수
  있으므로 합성 RED로 주장하지 않았다.
- 실제 helper 전환 전후의 동일한 `RedisTestSupportTest` 결과를 baseline과
  post-refactor로 비교했다.
- Docker-free 검증은 세 selector를 하나의 `:shared:test` invocation으로
  실행하고, helper source delegation guard를 별도 테스트로 두었다. 최종
  보정 후에는 key별 최신 supplier 검증까지 포함해 11개 테스트를 통과했다.
- 최소 `@SpringJUnitConfig(classes = [TestConfig::class])`와 nested
  `@Configuration(proxyBeanMethods = false)`, `@Bean` fake server,
  `@EnableConfigurationProperties` binding으로 실제 Environment 경계를
  검증했다.
- container workflow는 helper targeted, full `shared`, full Redis consumer를
  별도 순차 invocation으로 실행하며 CI `env:`에
  `TESTCONTAINERS_RYUK_DISABLED`와 `DOCKER_HOST`를 명시한다.

## 검증 결과

| 단계 | 명령/결과 |
|---|---|
| baseline | `:shared:test --tests '*RedisTestSupportTest' --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain` — 1 passed |
| Docker-free RED | 위임 전 3 selector 실행 — 10 tests 중 source guard 1 failure(수동 `registry.add` 확인) |
| Docker-free GREEN | 위임 후 동일 3 selector — 10 tests passed |
| review correction | Gradle `projectDir` 고정 source guard와 key별 최신 map 재평가 assertion 추가 후 동일 selector — 11 tests passed |
| dependency | `shared`와 `spring-data-redis-examples` `testRuntimeClasspath` dependencyInsight 모두 `io.github.bluetape4k:bluetape4k-testcontainers-spring:2.0.0` 선택 |
| post-refactor targeted | `:shared:test --tests '*RedisTestSupportTest'` — 1 passed |
| shared full | `:shared:test --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain` — 53 passed |
| Redis consumer full | `:spring-data-redis-examples:test --rerun-tasks --no-build-cache --no-daemon --max-workers=1 --console=plain` — 39 passed, 1 skipped |
| workflow/script | `actionlint .github/workflows/Examples.yml`, `bash -n scripts/smoke-validate.sh`, `git diff --check` — 통과 |

로컬 container 검증은 실행 중인 Colima Docker context와 관리된
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`를 사용했다. CI workflow에는 Linux
runner용 `DOCKER_HOST=unix:///var/run/docker.sock`를 step-level로 명시했다.

## 남은 위험

- `PropertyExportingServer.properties()`의 `port` 값은 upstream 계약상
  문자열이다. 기존 helper 테스트는 Spring dynamic property 경계에 맞춰
  `redis.port.toString()`을 비교한다.
- source guard는 Gradle이 주입한 현재 `shared` `projectDir`의
  `src/main/kotlin` 경로만 검사한다. IDE 실행 fallback도 현재 working
  directory와 그 `shared` 자식 한 단계로 제한하며, 경로와 `exists()`를
  assertion에 포함해 checkout 간 오탐을 막는다.
- 이번 이슈는 bridge 자체 구현이나 모든 Testcontainers 소비자 migration을
  포함하지 않는다.

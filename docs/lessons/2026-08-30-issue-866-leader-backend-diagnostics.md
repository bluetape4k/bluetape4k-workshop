# Issue #866 leader backend diagnostics 관찰성 경계

## Context

기존 `leader/backend-comparison-lab`은 Redis, ZooKeeper, Kubernetes Lease의
선출·failover 의미를 결정론적으로 비교했지만 Spring Boot 운영 표면과의
연결은 보여주지 않았다. `bluetape4k-leader` 2.0.0-SNAPSHOT에 추가된
diagnostics SPI, Actuator endpoint/health indicator, `leader-micrometer`
instrumentation을 실제 backend client 없이 기존 비교 예제에 적용할 필요가
있었다.

## Decision or Finding

- `ProfiledLeaderElector`는 `LeaderBackendCatalog` profile을 upstream
  `LeaderBackendDescriptor`로 변환하고, leader operation은 `LocalLeaderElector`에
  위임한다. 따라서 descriptor와 observability 계약은 검증하면서 client,
  credential, 네트워크 수명주기는 만들지 않는다.
- Actuator diagnostics는 기본 passive 모드로 `NOT_CHECKED`를 반환한다.
  active health probe는 명시적으로 켜고 `250ms` bounded timeout을 사용한다.
- `UP`, `DOWN`, `UNKNOWN`, provider unsupported/exception, cancellation을
  upstream probe 경계와 Spring health status로 매핑한다. cancellation은
  `UNKNOWN`으로 안전하게 닫되 예외 상세나 자격 증명을 health detail로
  노출하지 않는다.
- Micrometer counter는 `leader.backend.connectivity` 이름과
  `backend.name`, `status`, `reason` low-cardinality tag만 사용한다.
  실제 backend connectivity와 failover 검증은 각 practice module의 범위로
  남긴다.
- 비교 lab의 실행 경계는 explicit `leader-micrometer` decorator이므로
  Micrometer Observation tracing은 기본 비활성화한다. diagnostics endpoint와
  health probe를 끄지 않고, tracing auto-configuration이 별도로 요구하는
  lease-extension 수명주기와 분리한다.

## Outcome

세 backend profile의 capability descriptor, passive endpoint, opt-in health,
bounded probe budget, status/reason 매핑, cancellation, unknown profile
fail-closed 동작을 18개 테스트로 고정했다. 양언어 README에는 실행 명령,
설정 키, 안전 경계, 의존성 alias를 추가했고 coverage matrix, Examples
workflow 설명, stale-check guard도 갱신했다.

초기 전체 `BackendComparisonLabApp` context에서 선택하지 않은 reactive 경로가
`org/reactivestreams/Publisher`와 `reactor/core/publisher/Mono`를 요구해 각각
`NoClassDefFoundError`가 발생했다. 이를 versionless BOM alias인
`reactive-streams`·`reactor-core`로 보완하고 tracing을 기본 비활성화했다.
최종 `ApplicationContextRunner`는 실제 `BackendComparisonLabApp`과 전체
`@EnableAutoConfiguration` import를 로드하며, `WebApplicationContextRunner`와
`MockMvc`가 `/actuator/leaderBackendDiagnostics` HTTP route까지 검증한다.
따라서 WebFlux starter를 끌어오지 않으면서도 MVC endpoint, blocking elector와
local suspend fallback의 공존, selector 고정, Spring AOP classpath 계약을
같은 실행 그래프에서 회귀 검증한다.

## Verification

다음 검증이 통과했다.

```text
./gradlew :leader-backend-comparison-lab:test \
  --tests '*LeaderBackendDiagnosticsProviderTest' \
  --tests '*LeaderBackendDiagnosticsPropertiesTest' \
  --tests '*LeaderBackendDiagnosticsContextTest' \
  --no-build-cache --rerun-tasks --no-parallel --max-workers=1 --console=plain
SUCCESS: Executed 18 tests
BUILD SUCCESSFUL

./scripts/smoke-validate.sh stale-check
No stale refs found.
Required workshop modules are registered.
Leader diagnostics endpoint and context coverage are registered.
No broken image links found.

./gradlew :leader-backend-comparison-lab:bootRun --args='--server.port=18092 --bluetape4k.leader.observability.backend-health.enabled=true --workshop.leader.probe-outcome=UP'
GET /actuator/health
HTTP 200; leaderBackend.connectivity=UP; reason=CONNECTED
```

전체 no-Testcontainers smoke도 실행했지만, Issue #866 변경 범위와 무관한
`virtualthreads-rules` 기존 테스트가 `StructuredSubtask` 심볼을 찾지 못해
컴파일 단계에서 중단됐다. 실패 파일은
`virtualthreads/rules/src/test/kotlin/io/bluetape4k/workshop/virtualthread/part3/StructuredConcurrencyExamples.kt`
이며 이 브랜치에서는 해당 모듈을 수정하지 않았다. 따라서 이 이슈의 검증은
위의 모듈 단위 `check`, aggregate `detekt`, stale-check, 그리고 실제
`bootRun` smoke 증거로 판단하고, 전체 smoke 실패는 별도 baseline 복구
작업으로 추적한다.

## Future Guidance

새 leader backend 예제를 추가할 때는 descriptor capability와 passive
`NOT_CHECKED`를 먼저 고정하고, bounded active probe를 별도 opt-in으로 둔다.
지원하지 않는 probe는 `UNKNOWN`으로 처리하고 cancellation/interruption을
삼키거나 원시 예외·credential·endpoint를 detail과 metric tag에 넣지 않는다.
실제 backend client와 failover 시나리오는 이 credential-free 비교 lab이 아닌
backend별 practice module에서 Testcontainers 또는 명시적인 live opt-in으로
검증한다.

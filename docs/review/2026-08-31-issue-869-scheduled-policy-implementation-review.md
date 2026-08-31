# Issue #869 scheduled-policy 구현 review

## 범위와 판정

- 대상: `feat/issue-869-scheduled-policy` → `develop`, `leader/tenant-scheduler`
- 목적: 계획 승인 후 구현된 `2.0.0-SNAPSHOT` consumer 예제가 실제 Spring
  runtime proxy, YAML policy binding, task lifecycle, observation 보안 경계를
  증명하는지 6개 관점과 main integration으로 확인한다.
- 주요 근거: `TenantScheduledPolicyConfiguration.kt`,
  `TenantScheduledPolicyFixture.kt`, `application-scheduled-policy.yml`,
  `TenantScheduledPolicyContextTest.kt`, `TenantScheduledPolicyLifecycleTest.kt`,
  `TenantScheduledPolicyDefaultProfileTest.kt`, 양국어 module/root README,
  `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml`, Issue #869
  설계·계획·lesson.

최종 판정은 `PASS`다. 현재 diff에서 미해결 `P0=0`, `P1=0`, `P2=0`, `P3=0`이며,
이전 review에서 발견된 P2/P3는 구현·문서·guard·테스트로 처분했다. 외부 CI는 PR
생성 후 exact head에서 확인할 pending 항목이고, merge는 이 review 범위가 아니다.

## 6-lens 결과

| 관점 | 근거와 확인 내용 | 결과/처분 |
|---|---|---|
| 성능 | packaged YAML의 `min-lease-time=5s`를 direct smoke가 실제로 사용하고 두 호출 합계에 `assertTimeout(Duration.ofSeconds(15))`를 적용한다. scheduler trigger/edge-case runner는 `min-lease-time=0s`로 bounded 신호만 확인한다. 단일 local task 예제이므로 별도 benchmark는 범위가 아니다. | `P0/P1=0`; effective period가 최소 약 10초가 될 수 있다는 README/lesson 설명으로 P2를 처분했다. |
| 안정성·lifecycle | immediate `@Scheduled` callback이 `CountDownLatch`로 실제 실행되고 leader acquire/execution observation을 남긴다. pending task close, in-flight close, `ScheduledTaskHolder` 1→0, fixture의 custom executor/thread 부재를 별도 context에서 확인한다. | `P0/P1=0`; callback·close 경로를 추가해 기존 P1을 해소했다. 외부 backend failover는 local-only 예제 범위 밖이다. |
| 보안·데이터 경계 | YAML은 `allow-method-invocation=false`, static bounded name, lock tag `REDACT/redacted-lock`, tracing exception detail false를 사용한다. 성공·실패 observation 모두 raw policy/customer identifier와 throwable detail이 없음을 검사한다. `FAIL_OPEN_RUN`은 README에서 idempotent trusted override로 제한한다. | `P0/P1=0`; exception redaction negative assertion과 운영 경고로 P2를 처분했다. 외부 secret/SpEL parser 전체는 upstream 계약에 위임한다. |
| 운영·복구 | `bootRun`은 `Started TenantSchedulerLabAppKt`를 출력하고 fixture callback은 bounded `invocationCount` 로그를 남긴다. 양국어 README에 initial delay, min lease 추가 지연, `Ctrl-C`, 외부 enable override 제거 선행 rollback, default profile 확인 절차를 기록했다. stale guard와 workflow job이 실행 명령·dependency alias·핵심 YAML 계약을 검사한다. | `P0/P1=0`; runbook·callback signal·stale guard 누락 P1/P2를 해소했다. hosted ecosystem scope 누락은 manifest 보정으로 처분했고 exact-head CI 재실행을 pending으로 둔다. |
| API·개발자 경험 | module은 root `bluetape4k-dependencies` BOM만 사용하고 `leader-spring-boot`/`leader-micrometer` alias는 versionless다. plain `@Scheduled`와 `@LeaderElection`/`@LeaderGroupElection`/`@LeaderScheduled` precedence, exact selector, duplicate/unmatched/overload, semantic duration failure를 테스트로 고정했다. | `P0/P1=0`; annotation 범위·overload 회귀와 dependency contract를 증명했다. |
| 사용자·호출자 | profile은 opt-in이고 default reducer 동작은 유지된다. root와 module의 영어/한국어 README가 같은 실행 명령·selector·YAML·task 수·failure mode·local-only 제한을 제공하며 parity가 통과했다. | `P0/P1=0`; locale/예제 설명 drift 없음. |

## Main integration 검증

1. `build.gradle.kts`는 기존 root BOM 경계를 유지하면서 두 leader integration alias와
   observation runtime을 추가한다. 별도 Freefair/AspectJ CTW plugin은 추가하지 않았다.
2. `@Profile("scheduled-policy")` configuration이 fixture, `@EnableScheduling`,
   `@EnableAspectJAutoProxy(proxyTargetClass = true)`만 선언한다. registry, BPP,
   local factory, scheduler engine은 upstream auto-configuration과 Spring이 소유한다.
3. `application-scheduled-policy.yml`은 exact selector와 `SKIP`, 30초 lease,
   5초 min lease, static name, REDACT sentinel, strict/SpEL/tracing 경계를 함께
   binding한다. `history.retention.enabled=false`는 unrelated retention job의
   strict validation을 끄는 profile-local 설정이다.
4. profile context는 main-source `open` fixture를 proxy target으로 확인하고,
   auto-configuration imports의 factory → policy → metrics/observation → AOP 순서를
   확인한다. default context는 `tenantScheduledPolicyFixture` policy infrastructure와
   task가 없음을 검사하며 unrelated retention task는 범위에서 제외한다.
5. upstream external CTW singleton은 source/artifact smoke에서
   `NoAspectBoundException`과 `NoSuchMethodError`를 재현했으므로 최종 실행 경계로
   채택하지 않았다. 이 consumer는 Spring runtime proxy로 검증하며 upstream artifact
   수정은 별도 이슈 범위다.

## 이전 findings 처분

| finding | 처분 |
|---|---|
| 정상 context test가 selector/name/duration을 inline으로 덮던 문제 | packaged YAML에서 정상 binding하고, inline 값은 failure/edge-case와 tracing override에만 남겼다. |
| 실제 Spring scheduler callback 부재 | `LeaderScheduledTriggerFixture`와 immediate latch로 callback, task 1개, leader observations를 검증한다. |
| AOP opt-out가 task/body를 증명하지 못하던 문제 | plain scheduled task 1개, 실제 body invocation, leader infrastructure/observation 부재를 고정했다. |
| rollback·startup·callback 신호 부재 | 양국어 README에 runbook, startup token, bounded callback log를 추가했다. |
| stale guard가 dependency/운영 계약을 보호하지 못하던 문제 | 두 leader alias, YAML security keys, tests, README signals와 root profile command까지 guard에 추가했다. |
| direct smoke의 lease floor/timeout 문서 불일치 | production `5s` floor를 그대로 검증하고 15초 상한을 사용하도록 spec/plan/lesson을 정정했다. |
| default context의 unrelated retention task를 전체 task 부재로 오해할 위험 | fixture 선언 task만 부재인지 확인하고 README/plan/spec에 unrelated task 경계를 명시했다. |
| upstream CTW 실행 결함 | Freefair plugin을 제거하고 runtime proxy를 최종 결정으로 고정했다. |
| hosted assertion governance가 legacy import를 거부한 문제 | `kotlin.test.assertNull` 사용을 Bluetape assertion의 `shouldBeNull()`로 치환한 `8f8ec52833dbba77e9546532e1decb47d294448b` 커밋을 만들고 assertion governance 및 39개 테스트를 재실행했다. |
| hosted ecosystem reuse gate가 Issue #869 변경 경로를 찾지 못한 문제 | `docs/ecosystem-reuse-train.json`에 branch/base와 모든 PR 변경 경로를 담은 `issue-869-leader-scheduled-policy` follow-up scope를 추가하고, 새 `coordinator_scope_receipt`(`20260901T-issue-869-scheduled-policy-scope`, scope canonical SHA-256 `85cd0f5b18ae3cb28e064e0c390f48ff4202238e3638461c5887b6d71462fb08`)를 발행했다. exact `--pr-scope` checker를 로컬에서 통과시켰다. |

## 검증 증거

| 명령 | 결과 |
|---|---|
| `./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --no-build-cache --no-daemon --console=plain` | `BUILD SUCCESSFUL` |
| `./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:test --no-build-cache --no-daemon --console=plain` | `SUCCESS: Executed 39 tests in 12.1s`, `BUILD SUCCESSFUL` |
| context targeted test (`cleanTest`, `--no-build-cache`) | `SUCCESS: Executed 16 tests in 11.8s`, `BUILD SUCCESSFUL` |
| default-profile targeted test (`cleanTest`, `--no-build-cache`) | `SUCCESS: Executed 1 tests in 1.3s`, `BUILD SUCCESSFUL` |
| lifecycle targeted test (`cleanTest`, `--no-build-cache`) | `SUCCESS: Executed 3 tests in 703ms`, `BUILD SUCCESSFUL` |
| `./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy' --no-daemon --console=plain` (18초 bounded) | `Started TenantSchedulerLabAppKt in 0.664 seconds`; 60초 initial delay 때문에 callback은 기다리지 않음 |
| `./gradlew :leader-tenant-scheduler:dependencies --configuration runtimeClasspath --no-daemon --console=plain` | root `bluetape4k-dependencies:2.0.0-SNAPSHOT`; leader child artifact는 해당 upstream child train의 `1.0.0-SNAPSHOT`; Spring AOP 7.0.8; module explicit version/BOM 없음 |
| `./gradlew :leader-tenant-scheduler:buildEnvironment --no-daemon --console=plain` | `BUILD SUCCESSFUL`; 별도 AspectJ plugin 없음 |
| `./gradlew projects --no-daemon --console=plain` | `:leader-tenant-scheduler` 및 active modules 131 확인 |
| `node scripts/validate-readme-parity.mjs leader/tenant-scheduler` | `failures: 0` |
| `bash scripts/smoke-validate.sh stale-check` | project/stale/module/tenant scheduled-policy/diagnostics/image guards 통과 |
| `python3 .github/scripts/check-assertion-governance.py` | `PASS assertion governance: scanned=1168, allowlisted_build_logic_legacy_imports=16` |
| `python3 .github/scripts/check-ecosystem-reuse.py --pr-scope ...` | manifest에 `issue-869-leader-scheduled-policy` scope 추가 후 `PASS ecosystem-reuse inventory and train contract` |
| `actionlint .github/workflows/Examples.yml` | exit 0 |
| `git diff --check` | exit 0 |
| 신규·의미 변경 한국어 문서 7개 terminology audit | `findings=0` |
| added-line placeholder scan (governance prose 제외) | 미완료 marker 없음 |
| `./gradlew :leader-tenant-scheduler:detekt` | task 미등록(`Cannot locate tasks ... detekt`); `N/A (repository module has no detekt task)` |

## N/A와 남은 범위

- external Redis/DB failover, multi-node ownership, hot reload, coroutine/suspend
  scheduler, Exposed boundary, JDK preview, custom scheduler/executor는 이 consumer
  예제의 목표가 아니며 upstream/별도 issue로 남긴다.
- architecture/sequence diagram은 기존 reducer 경계를 섞지 않으므로 수정하지 않았다.
- PR #911은 `develop`을 base로 생성되었고, 첫 hosted assertion 실패와 두 번째
  ecosystem scope 실패를 각각 수정했다. 현재 exact head에서 CI를 재실행 중이며,
  live review/thread 확인과 merge는 아직 남아 있다. merge는 fresh `승인` 없이는
  실행하지 않는다.

## SPW 및 Kotlin DoD

- [x] `SPW-01`: 유지보수자 독자, Issue #869 목표, current source/test/docs 경로,
  upstream CTW 결함과 unsupported 범위를 고정했다.
- [x] `SPW-02`: review scope, 6-lens findings, main integration, severity/disposition,
  N/A와 verification contract를 포함했다.
- [x] `SPW-03`: 한국어 technical register와 code/API token 보존을 적용하고
  변경 의미 문서 7개 terminology audit을 `findings=0`으로 통과했다.
- [x] `SPW-04`: source, upstream artifact, test output, README/workflow/stale guard를
  대조해 stale claim을 정정했다.
- [x] `SPW-05`: Markdown을 다시 읽고 표·code fence·명령·DoD 구조를 확인했다.
- [x] `KT-FIN-01`~`KT-FIN-11`: touched Kotlin/Spring/test/docs를 재검토하고
  fresh compile/test, lifecycle, diff evidence를 수집했다. Exposed/HTTP/coroutine/
  module setup checklist는 trigger 없음으로 `N/A`다.

## DoD Status

`PASS (P0=0, P1=0, P2=0, P3=0; local verification complete; PR #911 exact-head hosted CI/review pending)`

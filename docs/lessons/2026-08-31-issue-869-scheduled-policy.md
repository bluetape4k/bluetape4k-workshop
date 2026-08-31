# Issue #869 scheduled-policy 구현 lesson

## Context

`leader/tenant-scheduler`는 기존 logical-tick reducer만 검증하고 있었으므로,
`bluetape4k-dependencies:2.0.0-SNAPSHOT`에서 제공하는
`bluetape4k-leader-spring-boot`의 YAML scheduled policy 경로를 별도
`scheduled-policy` profile로 추가했다. 기본 profile의 결정론적 reducer와 기존
테스트를 바꾸지 않고, Spring이 소유하는 task 등록·trigger·context close 경계를
실제 main source fixture로 확인하는 것이 목표였다.

## Decision

- 버전은 root `bluetape4k-dependencies` BOM이 관리하고, module에는 versionless
  `leader-spring-boot`와 `leader-micrometer` alias만 추가했다.
- upstream README가 안내하는 external AspectJ CTW 경로를 먼저 source build와
  consumer fixture로 시도했지만, `LeaderElectionAspect.aspectOf()`가 존재하지 않는
  no-arg constructor를 호출해 다음 오류가 재현됐다.
  `NoAspectBoundException: Exception while initializing ... LeaderElectionAspect`와
  `NoSuchMethodError: ... method 'void <init>()' not found`.
- 따라서 이 consumer는 Freefair/AspectJ CTW plugin을 추가하지 않고,
  `@EnableAspectJAutoProxy(proxyTargetClass = true)`와 `spring.aop.auto=false`를
  조합한 Spring runtime proxy를 사용한다. fixture는 CGLIB 대상인 `open class`와
  `open fun`으로 유지한다.
- YAML은 exact selector
  `tenantScheduledPolicyFixture#reconcile`, static bounded name,
  `localLeaderElectionFactory`, `SKIP`, `wait-time=0s`, `lease-time=30s`,
  `min-lease-time=5s`, `REDACT/redacted-lock`을 고정한다. 프로파일 YAML의
  unrelated strict startup validator가 기본 retention job을 해석하지 않도록
  `history.retention.enabled=false`를 함께 둔다.
- `ApplicationContextRunner`는 empty/malformed/duplicate/unmatched/invalid
  policy와 AOP opt-out을 fail-fast로 검증하고, `@SpringBootTest`는 packaged YAML,
  main fixture proxy, leader observation을 검증한다. `@LeaderElection`,
  `@LeaderGroupElection`, `@LeaderScheduled` precedence와 overloaded selector도
  fixture/registry test로 고정했다. scheduler wrapper의 Observation은 direct bean
  호출이 우회하므로 acceptance에서 주장하지 않고, 실제 `initialDelay=0` Spring
  scheduler callback과 task lifecycle은 별도 bounded test로 분리했다.

## Outcome

- `scheduled-policy` profile에서 Spring task가 정확히 하나 등록되고, main fixture
  direct call 두 번이 `leader.aop.acquire`와 `leader.aop.execution` observation을
  각각 남긴다.
- 실제 scheduler callback fixture도 첫 실행을 bounded latch로 확인하고 같은 leader
  observation과 REDACT lock tag를 남긴다. 기본 profile에는 task와 policy
  infrastructure가 없다.
- lock-name observation은 `redacted-lock`만 노출하며
  `tenant-scheduler:reconcile` 원문은 high-cardinality 기록에 나타나지 않는다.
- `@LeaderScheduled` 명시 annotation은 property policy보다 우선하고,
  `bluetape4k.leader.aop.enabled=false`에서는 leader infrastructure가 빠진
  plain scheduler task 경계가 고정됐다.
- pending task close, 즉시 trigger, in-flight callback close를 별도 context에서
  검증하며 예제가 executor/thread를 직접 만들지 않는다.
- 양국어 module README는 같은 profile 명령·selector·policy·lifecycle 제한을
  설명하고 root README/coverage matrix/stale guard/workflow에 등록했다.

## Verification

실제 실행 증거는 다음과 같다.

| 검증 | 결과 |
|---|---|
| `./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:compileKotlin :leader-tenant-scheduler:compileTestKotlin --no-build-cache --no-daemon --console=plain` | `BUILD SUCCESSFUL` (fresh compile) |
| `./gradlew :leader-tenant-scheduler:clean :leader-tenant-scheduler:test --no-build-cache --no-daemon --console=plain` | `SUCCESS: Executed 39 tests in 12.1s`, `BUILD SUCCESSFUL` |
| `./gradlew :leader-tenant-scheduler:cleanTest :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyContextTest*' --no-build-cache --no-daemon --console=plain` | `SUCCESS: Executed 16 tests in 11.8s`, `BUILD SUCCESSFUL` |
| `./gradlew :leader-tenant-scheduler:cleanTest :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyDefaultProfileTest*' --no-build-cache --no-daemon --console=plain` | `SUCCESS: Executed 1 tests in 1.3s`, `BUILD SUCCESSFUL` |
| `./gradlew :leader-tenant-scheduler:cleanTest :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyLifecycleTest*' --no-build-cache --no-daemon --console=plain` | `SUCCESS: Executed 3 tests in 703ms`, `BUILD SUCCESSFUL` |
| hosted Smoke 실패 후 context targeted test 재실행 (`./gradlew :leader-tenant-scheduler:cleanTest :leader-tenant-scheduler:test --tests '*TenantScheduledPolicyContextTest*' --no-build-cache --no-daemon --console=plain`) | `SUCCESS: Executed 16 tests in 11.8s`, `BUILD SUCCESSFUL`; callback observation stop race 보정 확인 |
| exact head `8231322c22d7c3e59188cb50d4ea9bb6c30ffa19` hosted `CI` run `33418781214` | wrapper validation, compile-only build, CI Status 모두 PASS |
| exact head `8231322c22d7c3e59188cb50d4ea9bb6c30ffa19` hosted `Ecosystem Reuse Gate` run `33418781147` | ecosystem reuse contract PASS |
| exact head `8231322c22d7c3e59188cb50d4ea9bb6c30ffa19` hosted `Examples` run `33418781209` | Diagram QA, README/stale guards, Smoke, Container, High-contention, Examples Status 모두 PASS |
| `./gradlew :leader-tenant-scheduler:dependencies --configuration runtimeClasspath --no-daemon --console=plain` | `bluetape4k-dependencies:2.0.0-SNAPSHOT`와 core 계열, `bluetape4k-leader-spring-boot:1.0.0-SNAPSHOT`, `bluetape4k-leader-micrometer:1.0.0-SNAPSHOT`, Spring AOP 7.0.8 확인; module catalog에는 개별 버전/BOM 없음 |
| `./gradlew :leader-tenant-scheduler:buildEnvironment --no-daemon --console=plain` | `BUILD SUCCESSFUL`; 별도 AspectJ plugin 없음 |
| `./gradlew projects --no-daemon --console=plain` | `:leader-tenant-scheduler` 실제 project path 확인 |
| `./gradlew :leader-tenant-scheduler:bootRun --args='--spring.profiles.active=scheduled-policy' --no-daemon --console=plain` (18초 bounded smoke) | `Started TenantSchedulerLabAppKt in 0.664 seconds`; 첫 callback은 `initialDelay=60s` 뒤라 이 smoke에서는 기다리지 않음 |
| `node scripts/validate-readme-parity.mjs leader/tenant-scheduler` | `failures: 0` |
| `bash scripts/smoke-validate.sh stale-check` | required modules, scheduled-policy contract, diagnostics, image links 모두 통과 |
| `rg`를 제외한 임시 `PATH`에서 `bash scripts/smoke-validate.sh stale-check` 실행 | `rg` 없이도 project/stale/module/tenant scheduled-policy/diagnostics/image guards 모두 통과 |
| `actionlint .github/workflows/Examples.yml` | exit 0 |
| `git diff --check` | exit 0 |
| `python3 .github/scripts/check-ecosystem-reuse.py --pr-scope --base-ref-name develop --head-ref-name feat/issue-869-scheduled-policy --base-ref 4b21288bbfbfddb5438baf763027149766919bc5 --head-ref 8f8ec52833dbba77e9546532e1decb47d294448b` | manifest 보정 전에는 `found 0`으로 실패했으나, `issue-869-leader-scheduled-policy` follow-up scope 추가 후 `PASS ecosystem-reuse inventory and train contract` |
| `node .../audit-korean-terms.mjs` (신규·변경 의미 문서 7개) | `findings=0`; root README/coverage의 기존 `snapshot` 용례는 변경하지 않음 |
| added-line placeholder scan | `TODO/FIXME/TBD/XXX/TEMP/PLACEHOLDER/WIP` 없음 |

전체 root README/coverage 파일을 대상으로 한 terminology audit은 기존 표에
남아 있던 `snapshot` 용례 10건을 보고했지만, 이번 변경에서 그 문장을
수정하지 않았다. 신규·의미 변경 문서 7개를 별도로 다시 검사한 결과는
`findings=0`이며, `2.0.0-SNAPSHOT` 같은 버전 token은 그대로 보존했다.

## Miss/Surprise

1. Gradle의 `:leader:tenant-scheduler` 축약 경로는 여러 `leader-*` project와
   충돌했다. 이후 모든 검증에서 실제 평면 project 이름
   `:leader-tenant-scheduler`를 사용했다.
2. CTW compile/weave 성공만으로 runtime singleton 경로가 유효하다고 판단할 수
   없었다. upstream artifact의 `NoAspectBoundException`을 직접 확인한 뒤에야
   proxy 경계를 선택했다.
3. `TagRule`의 부분 nested binding은 redaction sentinel을 기본값 `redacted`로
   만들었다. profile YAML에 `redacted-value: redacted-lock`을 명시하고 테스트에서
   값을 고정해야 했다.
4. 테스트 전용 Observation handler를 수동 등록하면 Boot post-processor와 중복되어
   observation이 두 번 기록됐다. `ObservationRegistry`만 primary bean으로 제공하고
   handler lifecycle은 Boot/upstream에 맡겨 중복을 제거했다.
5. profile list를 외부 indexed property로 일부 override하면 packaged policy list가
   통째로 대체된다. 정상 profile test는 selector부터 모든 policy index를 명시해
   실제 YAML binding 계약을 흐리지 않도록 했다.
6. `@SpringBootTest` direct call은 packaged YAML의 5초 `min-lease-time` floor를
   의도적으로 포함하므로 두 번 호출에 약 10초가 걸릴 수 있다. 반면 scheduler
   callback/edge-case runner는 `min-lease-time=0s`를 사용해 trigger 신호만 bounded하게
   확인한다. 따라서 README의 fixed delay와 effective period를 분리해 설명해야 한다.
7. 첫 hosted CI는 `kotlin.test.assertNull` legacy assertion import를 발견했다.
   `shouldBeNull()`로 치환한 `8f8ec52833dbba77e9546532e1decb47d294448b` 커밋 후
   assertion governance와 39개 테스트를 다시 통과시켰다.
8. 두 번째 hosted CI는 Issue #869 변경 경로가 ecosystem reuse train의 follow-up
   scope에 매핑되지 않아 실패했다. `issue-869-leader-scheduled-policy`에 정확한
   branch/base와 이번 PR의 모든 변경 경로를 등록했고, 같은 `--pr-scope` 명령으로
   로컬에서 재현·통과를 확인했다.
9. 세 번째 hosted CI는 follow-up scope를 추가하면서 기존 coordinator receipt를
   재사용한 것을 거부했다. 새 `coordinator_scope_receipt`를 발행하고 scope
   canonical JSON SHA-256 `85cd0f5b18ae3cb28e064e0c390f48ff4202238e3638461c5887b6d71462fb08`
   를 연결해야 trusted manifest 비교까지 통과한다.
10. 네 번째 hosted Examples 실행은 Ubuntu runner에 `rg`가 없어서 기존
    `smoke-validate.sh`의 stale-check가 scheduled-policy 계약을 검사하기 전에
    실패했다. 로컬에서 `rg`를 숨긴 PATH로 같은 stale-check를 재현한 뒤,
    파일·디렉터리 검색과 이미지 링크 추출을 `grep` 기반 helper로 바꾸어
    runner 도구 설치에 의존하지 않도록 고정했다.
11. 다섯 번째 hosted Smoke 실행은 scheduler fixture가 body 안에서 시작 latch를
    먼저 해제한 뒤 execution observation이 stop되기 전에 assertion을 수행하는
    경합을 드러냈다. observation handler에 기대 stop 수를 세는 bounded latch를
    추가하고, callback body 신호와 observation 완료 신호를 모두 기다리도록
    고정했다.
12. 최신 exact head에서 hosted `CI`, `Ecosystem Reuse Gate`, `Examples`가 모두
    통과했다. 특히 Examples high-contention matrix도 성공해, 다섯 차례의
    실패 원인 처분이 실제 runner 조합에서 재검증되었다.

## Future guard

- upstream `LeaderElectionAspect`가 정상적인 external CTW singleton 초기화를 제공할
  때만 CTW 경로를 재검토한다. 그 전에는 consumer에 weaving plugin을 추가하지 않는다.
- scheduled-policy profile은 local factory를 사용하는 단일 프로세스 학습 경로다.
  실제 backend failover, distributed ownership, hot reload, coroutine/suspend
  scheduler는 별도 issue와 upstream 계약으로 다룬다.
- Spring proxy fixture에는 `open` 요구를 유지하고, `spring.aop.auto=false`와
  explicit `@EnableAspectJAutoProxy`의 단일 proxy 조합을 stale guard와 context
  test에서 함께 검사한다.
- README parity와 stale-check를 workflow job에서 계속 실행하고, YAML selector,
  redaction sentinel, profile 명령이 바뀌면 양국어 README와 테스트를 같은 변경으로
  갱신한다.
- stale-check는 GitHub runner에 선택적으로 설치된 도구를 전제하지 않는다. 검색은
  `grep` helper와 표준 `find`를 사용하고, 새 명령을 추가할 때는 `rg`가 없는
  `PATH`에서도 `bash scripts/smoke-validate.sh stale-check`를 재실행한다.
- scheduler callback 테스트는 body 진입 latch만으로 observation 완료를 단정하지
  않는다. leader acquire/execution stop 신호를 별도 bounded latch로 기다린 뒤
  outcome과 redaction을 검사한다.
- 새 workshop PR은 생성 전에 `docs/ecosystem-reuse-train.json`에 정확한
  `expected_head_ref`/`expected_base_ref`와 changed-path `allowed_paths`를 하나의
  follow-up scope로 등록하고, scope canonical JSON SHA-256과 새
  `coordinator_scope_receipt`를 함께 발행한 뒤 `check-ecosystem-reuse.py --pr-scope`를
  실행한다. 기존 receipt ID/checksum 재사용은 금지한다.

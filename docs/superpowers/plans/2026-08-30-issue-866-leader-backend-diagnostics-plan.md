# #866 leader backend diagnostics observability 구현 계획

## 목표

`leader/backend-comparison-lab`이 `bluetape4k-dependencies:2.0.0-SNAPSHOT`의
`leader-spring-boot`와 `leader-micrometer`를 사용해 선택 profile의 정적
diagnostics, opt-in bounded health probe, low-cardinality connectivity metric을
실제 Spring Boot 운영 표면으로 보여 주게 한다. 기본 테스트와 smoke는 외부
backend 없이 통과해야 한다.

## 전제와 범위

- 작업 branch: `feat/issue-866-leader-diagnostics`
- base: `develop` @ `985beb08a0e16bec92dcd68d17bdb7a2e2b2ffc1`
- 이슈: [#866](https://github.com/bluetape4k/bluetape4k-workshop/issues/866)
- 모듈: `leader/backend-comparison-lab`
- 쓰기 범위: 이 모듈 소스/테스트/README, `gradle/libs.versions.toml`,
  `docs/coverage-matrix.md`, `.github/workflows/Examples.yml`,
  `scripts/smoke-validate.sh`, `docs/superpowers/`, `docs/review/`,
  `docs/lessons/`
- 제외: 실제 backend client/컨테이너, write endpoint, release version pin,
  unrelated module refactor

## 의존성 순서와 작업

### 1. 스펙/계획 리뷰와 upstream 계약 고정

1. `docs/superpowers/specs/2026-08-30-leader-backend-diagnostics-design.md`의
   upstream API 이름, 상태 매핑, 보안/네트워크 경계를 실제 sibling source와
   Maven metadata에 대조한다.
2. `bluetape4k-full-feature` Step 2-R/3-R 여섯 관점과 통합 검토를 수행한다.
   P0/P1은 0이어야 하며, P2/P3는 이 계획에 반영하거나 명시적으로 유보한다.
3. `docs/review/2026-08-30-issue-866-spec-plan-review.md`에 근거, 발견,
   수정, 최종 verdict를 기록한다.
4. writer gate를 통과한 spec/plan/review를 Lore commit으로 저장한다.

검증:

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/specs/2026-08-30-leader-backend-diagnostics-design.md \
  docs/superpowers/plans/2026-08-30-issue-866-leader-backend-diagnostics-plan.md \
  docs/review/2026-08-30-issue-866-spec-plan-review.md
```

실패 시 구현을 시작하지 않고 해당 문서와 review를 먼저 고친다.

### 2. BOM alias와 모듈 의존성 변경

파일:

- `gradle/libs.versions.toml`
- `leader/backend-comparison-lab/build.gradle.kts`

작업:

1. `[libraries]`에 다음 versionless alias를 추가한다.
   `bluetape4k-leader-spring-boot`와 `bluetape4k-leader-micrometer`는
   `io.github.bluetape4k.leader:*` 좌표만 적는다.
2. 모듈 `dependencies`에 두 alias를 `implementation`으로 추가한다.
3. 개별 Bluetape BOM이나 명시 버전은 추가하지 않는다.

검증:

```bash
./gradlew :leader-backend-comparison-lab:dependencies \
  --configuration runtimeClasspath --console=plain
```

기대 결과는 두 artifact가 root `bluetape4k-dependencies`에서 해석되고
수동 Bluetape version 선언이 없는 것이다.

### 3. TDD: provider와 properties의 실패 테스트 작성

먼저 다음 테스트를 작성하고 production class가 없어서 실패하는지 확인한다.

파일:

- `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/LeaderBackendDiagnosticsProviderTest.kt`
- `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/LeaderBackendDiagnosticsPropertiesTest.kt`

필수 케이스:

1. 세 profile descriptor가 catalog id/display name/capability와 일치한다.
2. passive `diagnostics()`는 `NOT_CHECKED`이며 callback을 호출하지 않는다.
3. `UP`, `DOWN`, `UNKNOWN`, `UNSUPPORTED`, `EXCEPTION` 매핑이 각각
   `CONNECTED`, `DISCONNECTED`, `CLIENT_STATE_UNCONFIRMED`,
   `PROVIDER_UNSUPPORTED`, `PROVIDER_EXCEPTION` reason을 만든다.
4. `CANCELLED`는 같은 `CancellationException` 인스턴스를 재전달한다.
5. 250ms timeout이 callback에 전달되고 0/무한 timeout은 거부된다.
6. 알 수 없는 `backend-id`와 잘못된 `probe-outcome`은 properties binding에서
   fail closed 한다.

테스트는 JUnit 5, `bluetape4k-assertions`, `ApplicationContextRunner`와
descriptive backtick 이름을 사용한다. `assertThrows`나 `!!`는 사용하지 않는다.

실패 검증:

```bash
./gradlew :leader-backend-comparison-lab:test \
  --tests '*LeaderBackendDiagnosticsProviderTest' \
  --tests '*LeaderBackendDiagnosticsPropertiesTest' \
  --no-build-cache --rerun-tasks --no-parallel --max-workers=1 --console=plain
```

### 4. 최소 production 구현으로 red를 green으로 전환

파일:

- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/LeaderBackendDiagnosticsProperties.kt`
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/ProfiledLeaderElector.kt`
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/LeaderBackendDiagnosticsConfiguration.kt`
- `leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/BackendComparisonLabApp.kt`

작업:

1. `LeaderBackendDiagnosticsProperties`를 `workshop.leader` prefix로 등록하고
   기본 `backendId=redis-lettuce`, `probeOutcome=UNKNOWN`을 제공한다.
2. properties의 backend id는 생성자에서 `LeaderBackendCatalog.findById`로
   검증한다. outcome은 sealed/enum 값으로 제한한다.
3. `ProfiledLeaderElector`는 `LocalLeaderElector`에 leader 실행을 위임하고
   `LeaderBackendDiagnosticsProvider`를 직접 구현한다. descriptor는 현재
   upstream provider의 정적 capability 계약을 profile id에 맞춰 표현한다.
4. active probe는 `LeaderBackendDiagnosticsProbe.check`만 사용한다. callback은
   설정된 outcome을 반환하거나 일반 예외/취소를 발생시킨다. client, lock,
   lease, thread, executor, 네트워크를 만들지 않는다.
5. configuration은 `ProfiledLeaderElector`를 `InstrumentedLeaderElector`로
   감싸고 `MeterRegistry`를 주입한다. bean return type은 `LeaderElector`로
   선언해 `LocalLeaderConfiguration`의 local blocking fallback을 대체한다.
6. `bluetape4k.leader.observability.state-provider-bean`을
   `workshopLeaderElector`로 고정해 local suspend fallback과 공존할 때도
   selector가 명시적으로 같은 provider를 선택하게 한다.
7. `BackendComparisonLabApp`가 configuration을 import한다. public KDoc은
   한국어로 작성하고 기존 package prefix를 유지한다.

구현 중 각 red 테스트만 통과시키는 최소 수정으로 진행하고, broad catch에서
`CancellationException`을 삼키지 않는다.

### 5. Spring 운영 표면 통합 테스트

파일:

- `leader/backend-comparison-lab/src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/LeaderBackendDiagnosticsContextTest.kt`

`ApplicationContextRunner`에 `BackendComparisonLabApp`을 등록하고 다음을
검증한다.

1. 기본 context가 endpoint/provider를 만들며 endpoint 응답은 selected profile과
   `NOT_CHECKED`를 반환한다.
2. `management.endpoint.leaderBackendDiagnostics.enabled=false`이면 endpoint가
   만들어지지 않는다.
3. health를 켜고 `probe-outcome=UP/DOWN/UNKNOWN/UNSUPPORTED/EXCEPTION`을
   바꿨을 때 `LeaderBackendHealthIndicator`의 status와 detail이 계약대로
   바뀐다.
4. active health 한 번 뒤 `leader.backend.connectivity` counter가
   `backend`, `status`, `reason` tag로 정확히 하나 증가한다. raw exception,
   endpoint, token, credential이 metric이나 health detail에 나타나지 않는다.
5. cancellation은 health에서 `UNKNOWN`으로 닫히며 원래 cancellation은
   provider 단위에서 재전달된다.

검증:

```bash
./gradlew :leader-backend-comparison-lab:test \
  --tests '*LeaderBackendDiagnosticsContextTest' \
  --no-build-cache --rerun-tasks --no-parallel --max-workers=1 --console=plain
```

### 6. README와 catalog 표면을 source와 동기화

파일:

- `leader/backend-comparison-lab/README.md`
- `leader/backend-comparison-lab/README.ko.md`
- `leader/backend-comparison-lab/src/main/resources/application.yml`
- 필요 시 `LeaderBackendCatalog.kt`의 diagnostics 설명/metrics row

작업:

1. dependencies 예제에 두 alias를 추가한다.
2. `/actuator/leaderBackendDiagnostics`, `/actuator/health` 호출 예제와
   `workshop.leader.backend-id`, `workshop.leader.probe-outcome`,
   `bluetape4k.leader.observability.backend-health.timeout`과
   `bluetape4k.leader.observability.state-provider-bean` 설정을 두 locale에
   같은 순서로 설명한다.
3. passive `NOT_CHECKED`와 active 상태 표, bounded reason, metric tag,
   민감정보 비노출, 실제 backend로 이동하는 경계를 명시한다.
4. 기본 설정은 health/endpoint class가 로드되어도 실제 backend/network 없이
   동작하도록 한다. `management.endpoints.web.exposure.include`에는
   endpoint id를 명시한다.
5. 기존 diagrams는 텍스트/구조 변경이 없으면 재생성하지 않고 review에 N/A
   근거를 남긴다. 문서 API 이름과 source를 다시 grep한다.

검증:

```bash
git diff --check
rg -n "leaderBackendDiagnostics|probe-outcome|NOT_CHECKED|leader.backend.connectivity" \
  leader/backend-comparison-lab/README.md leader/backend-comparison-lab/README.ko.md
```

### 7. 등록 표면과 smoke/full workflow 점검

파일:

- `docs/coverage-matrix.md`
- `.github/workflows/Examples.yml`
- `scripts/smoke-validate.sh`

작업:

1. coverage matrix의 leader row에 diagnostics endpoint/health/metric gap과
   #866 근거를 반영한다.
2. path filter, smoke group, full group, test result artifact가 이미 모듈을
   포함하는지 확인하고, 새 context test의 결과가 누락되지 않도록 필요한
   glob/comment만 수정한다.
3. `smoke-validate.sh stale-check`에 `leader/backend-comparison-lab`의
   README/config/observability registration을 검증하는 최소 guard를 추가한다.
4. workflow YAML을 건드리면 `actionlint`와 YAML parse 검사를 실행한다.

검증:

```bash
./scripts/smoke-validate.sh stale-check
./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks \
  --no-parallel --max-workers=1 --console=plain
```

### 8. 전체 검증과 performance/stability scan

순서:

1. `compileKotlin`, `compileTestKotlin`으로 import/API drift를 확인한다.
2. 모듈 전체 test를 fresh 실행한다.
3. smoke에서 모듈을 포함한 `all-smoke`를 sequential worker로 실행한다.
4. `performance-stability-scan.md`로 probe timeout 전달, cancellation 재전달,
   local delegate lifecycle, metric counter 중복, startup/shutdown을 검토한다.
5. concurrency helper가 필요한 실제 concurrent path는 추가하지 않는다. 이
   예제의 probe는 동기, stateless, 호출 thread 경계이므로 별도 stress harness는
   N/A로 기록한다.

명령:

```bash
./gradlew :leader-backend-comparison-lab:compileKotlin \
  :leader-backend-comparison-lab:compileTestKotlin --warning-mode all --console=plain
./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks \
  --no-parallel --max-workers=1 --console=plain
./scripts/smoke-validate.sh all-smoke
git diff --check
```

### 9. verifier, 7-tier review, lesson, PR

1. `step-5-verifier-checklist.md`로 spec/plan 요구사항, 변경 파일, fresh test,
   docs, known gaps를 매핑한다.
2. `docs/review/2026-08-30-issue-866-leader-diagnostics-code-review.md`에
   module slice 기준 여섯 관점 + 통합 검토를 기록한다. P0=0/P1=0이 될 때까지
   수정하고 해당 테스트를 다시 실행한다.
3. `docs/lessons/2026-08-30-issue-866-leader-backend-diagnostics.md`에 결정,
   개발 artifact drift 방지, cancellation/metric 경계, 검증 결과와 future
   guard를 기록한다.
4. 모든 Korean artifact에 writer SPW-01~05와 terminology audit를 완료한다.
5. Lore commit을 push하고, issue #866 metadata를 확인한 뒤 Korean PR을
   `develop`으로 생성한다. PR body 끝에는 `## DoD Status`와 fresh test/CI
   증거를 포함한다.

PR 생성 후에는 실제 head/CI/review/thread를 다시 읽고 Step 10 DoD에서 멈춘다.
merge는 새 head에 대한 별도 `승인` 없이는 실행하지 않는다.

## 위험·완화·rollback

| 위험 | 신호 | 완화 | rollback/rerun |
|---|---|---|---|
| 개발 artifact API drift | compile import/method failure | Maven metadata와 sibling source 재대조, implementation 중단 | alias/API commit 되돌리고 artifact 배포 후 Step 2부터 재실행 |
| local fallback이 provider로 선택됨 | endpoint descriptor가 `local`이거나 bean 후보 오류 | custom blocking elector bean type/selector 후보를 context test로 고정 | configuration bean 변경 후 context test 재실행 |
| health 호출마다 metric 중복 또는 tag explosion | registry meter count/tag cardinality 불일치 | `InstrumentedLeaderElector` 한 번만 감싸고 세 tag assertion | wrapper 생성 위치 수정 후 context test 재실행 |
| cancellation/interrupt 손실 | cancellation test가 정상 반환하거나 thread flag 소실 | `LeaderBackendDiagnosticsProbe`의 예외 경계를 그대로 사용 | provider 테스트부터 red/green 재실행 |
| 문서/CI 등록 drift | stale-check, README grep, workflow path 누락 | exact-file checklist와 actionlint | 등록 파일만 수정 후 smoke 재실행 |
| 기본 smoke가 외부 자격 증명 요구 | context startup/network call | local delegate와 passive default, no client dependency | application config/provider를 되돌리고 module test 재실행 |

## 완료 기준

- [ ] spec/plan/review writer gate 통과 및 Lore commit
- [ ] provider/properties/context 테스트가 fresh green
- [ ] default passive endpoint가 `NOT_CHECKED`, active health/metric 매핑 green
- [ ] cancellation/timeout/sensitive-data 경계가 테스트로 증명됨
- [ ] BOM alias만으로 의존성이 해석됨
- [ ] 두 README, application config, coverage matrix, workflow, stale-check 동기화
- [ ] compile, module test, smoke, diff check, actionlint(해당 시) fresh green
- [ ] Step 5 verifier와 Step 6-R P0=0/P1=0
- [ ] lesson commit 완료, PR metadata/body/CI 확인
- [ ] merge는 fresh explicit approval 대기

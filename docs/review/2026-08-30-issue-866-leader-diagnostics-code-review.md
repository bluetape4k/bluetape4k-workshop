# Issue #866 leader backend diagnostics 코드 리뷰

## 검토 범위와 판정

- 대상: `feat/issue-866-leader-diagnostics`의 Issue #866 변경 전체
- 기준: `origin/develop` (`985beb08a0e16bec92dcd68d17bdb7a2e2b2ffc1`)
- 구현 기준점: 기존 설계·계획 문서와 실제 `BackendComparisonLabApp` 실행 그래프
- 변경 원칙: 2.0.0-SNAPSHOT BOM versionless 소비, credential-free 기본 smoke,
  passive diagnostics와 opt-in bounded health probe

아키텍처 독립 리뷰는 `DONE/WATCH`로 P0 0, P1 0을 확인했다. 전역 health
detail 노출은 workshop 실습 설정의 P2 watch로 남겼고, passive/cancellation
metric 미기록 음성 증명과 coverage matrix의 Gap 의미는 이 리뷰 전에 보강했다.
코드 리뷰어 독립 판정도 별도 lane에서 `PASS/WATCH`로 완료했으며 P0/P1/P2/P3
신규 finding은 0건이다. 전체 통합 상태의 `WATCH`는 변경과 무관한 기존
`virtualthreads-rules` smoke 실패를 뜻한다.

## 여섯 관점 검토

### 1. 보안

- `ProfiledLeaderElector`는 실제 client, endpoint, credential, network를 만들지
  않는다 (`leader/backend-comparison-lab/src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/observability/ProfiledLeaderElector.kt:22-27`).
- health detail과 metric tag는 backend/status/reason 중심이며 exception 원문이나
  credential을 넣지 않는 테스트가 있다
  (`LeaderBackendDiagnosticsContextTest.kt:130-153`).
- `application.yml`은 실습 curl을 위해 `show-details: always`를 사용한다
  (`leader/backend-comparison-lab/src/main/resources/application.yml:7-16`). 이는
  credential-free lab의 로컬 관찰 편의를 위한 범위이며, production 서비스에서는
  `when-authorized` 또는 전용 health group으로 좁혀야 한다.

### 2. 정확성·API 계약

- 세 catalog profile을 upstream `LeaderBackendDescriptor` capability로 매핑하고
  실행 모델, lease extension, audit, clock, TTL/session을 정확히 검증한다
  (`ProfiledLeaderElector.kt:65-115`, `LeaderBackendDiagnosticsProviderTest.kt:24-58`).
- passive endpoint는 `NOT_CHECKED`를 반환하며 provider callback을 호출하지 않는다.
  active probe는 양수 유한 timeout과 `UP/DOWN/UNKNOWN/UNSUPPORTED/EXCEPTION`을
  upstream reason으로 정규화한다 (`ProfiledLeaderElector.kt:41-62`).
- `state-provider-bean=workshopLeaderElector`로 local suspend fallback과의
  selector 모호성을 제거했다 (`application.yml:23-34`). 알 수 없는 backend와
  provider bean은 context startup에서 fail-closed 한다
  (`LeaderBackendDiagnosticsContextTest.kt:98-114`).

### 3. 성능·안정성

- 기본 경로는 network I/O와 probe를 실행하지 않고, active 경로는 `250ms` bounded
  budget을 사용한다 (`application.yml:29-32`).
- `CancellationException`은 provider 경계에서 재전달하고 health에서는
  `UNKNOWN`으로 닫으며, cancellation에서 Micrometer meter를 만들지 않는 것을
  검증한다 (`LeaderBackendDiagnosticsProviderTest.kt:67-77`,
  `LeaderBackendDiagnosticsContextTest.kt:185-205`).
- `reactive-streams`와 `reactor-core`는 현재 2.0.0-SNAPSHOT 조건부 auto-config
  classpath를 만족하는 versionless `runtimeOnly` bridge이며 WebFlux starter를
  추가하지 않는다 (`build.gradle.kts:27-31`).

### 4. 테스트·검증

- diagnostics provider 8개, properties 2개, context/web 8개로 총 18개 테스트를
  통과했다. 기존 catalog 5개와 failover 4개를 포함한 module `check`는 총 27개
  테스트를 통과했다.
- 실제 `BackendComparisonLabApp`을 `@Import`하고 full
  `@EnableAutoConfiguration` graph를 로드하며, `WebApplicationContextRunner`와
  `MockMvc`로 `/actuator/leaderBackendDiagnostics` HTTP 200 route를 확인한다
  (`LeaderBackendDiagnosticsContextTest.kt:29-51`, `77-86`, `209-215`).
- active `UP`에서 meter 수·정확한 tag key·count를 검증하고, passive/cancellation
  경로에서는 `leader.backend.connectivity` meter가 비어 있음을 검증한다
  (`LeaderBackendDiagnosticsContextTest.kt:69-73`, `140-153`, `201-205`).

### 5. 문서·운영

- `README.md`와 `README.ko.md`에 passive/active 실행법, 상태·reason, selector
  설정, metric tag, credential/action/network 경계를 같은 순서로 기록했다
  (`README.md:20-64`, `README.ko.md:20-64`).
- `coverage-matrix.md`의 신규 행은 구현된 관찰성 계약을 Existing example로
  연결하고 Gap에는 실제 backend client lifecycle·failover와 credential 경계를
  남겼다 (`docs/coverage-matrix.md:118-122`).
- Examples workflow와 `scripts/smoke-validate.sh`에 모듈·context·YAML nesting을
  확인하는 guard를 등록했다 (`scripts/smoke-validate.sh:351-366`).
- `docs/lessons/2026-08-30-issue-866-leader-backend-diagnostics.md`에 snapshot
  classpath workaround와 전체 smoke의 기존 `virtualthreads-rules`
  `StructuredSubtask` 컴파일 실패를 별도 baseline gap으로 기록했다.

### 6. 유지보수성·설계

- 설정(`LeaderBackendDiagnosticsProperties`), profile adapter
  (`ProfiledLeaderElector`), Spring wiring(`LeaderBackendDiagnosticsConfiguration`)
  을 분리해 catalog와 observability 경계를 명확히 했다.
- 기본값은 결정론적 `redis-lettuce` + `UNKNOWN`이며, profile id와 probe outcome은
  enum/카탈로그 검증을 거쳐 알 수 없는 선택을 조기에 실패시킨다
  (`LeaderBackendDiagnosticsProperties.kt:12-40`).
- 새 backend를 추가할 때 capability 매핑 테스트, passive 기본값, bounded probe,
  cancellation·민감정보 경계를 함께 확장하도록 lesson과 stale guard가 남아 있다.

## 검증 증거

| 검증 | 결과 |
|---|---|
| diagnostics targeted test | PASS — 18 tests |
| `:leader-backend-comparison-lab:check` | PASS — 27 tests |
| aggregate `detekt` | PASS — 108 actionable tasks |
| `git diff --check origin/develop` | PASS |
| `scripts/smoke-validate.sh stale-check` | PASS — stale refs/required modules/leader guard/image links |
| `bash -n` + `shellcheck scripts/smoke-validate.sh` | PASS |
| `actionlint .github/workflows/Examples.yml` | PASS |
| YAML structure parse | PASS — `tracing`, `backend-health`, `state-provider-bean` sibling |
| manual `bootRun` passive | PASS — diagnostics `NOT_CHECKED`, health HTTP 200 |
| manual `bootRun` active | PASS — health `leaderBackend.connectivity=UP`, reason `CONNECTED` |
| full all-smoke | BASELINE GAP — unrelated `virtualthreads-rules` `StructuredSubtask` compile failure |

## 독립 리뷰 결론

- 아키텍처 reviewer: `WATCH`, P0 0 / P1 0 / 기존 P2 2·P3 1은 음성 metric
  assertion과 Gap 수정으로 해소. `show-details: always`만 비차단 P2 watch.
- code reviewer: `PASS/WATCH`, P0 0 / P1 0 / P2 0 / P3 0. selector,
  active/passive/cancellation metric, health details, versionless BOM, 실제 앱
  context와 MVC route에서 신규 차단 이슈가 없음을 확인했다.

최종 merge readiness는 두 독립 reviewer가 P0/P1 없이 완료되고, 원격 CI가
통과하거나 위 baseline failure가 이 변경과 무관하다는 증거가 유지될 때
`READY FOR REVIEW`로 표시한다. 이 문서는 merge 승인을 대신하지 않는다.

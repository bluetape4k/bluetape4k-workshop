# Issue #867 Type A workflow checklist

대상: `bluetape4k4k-workshop/leader/job-safety-lab`, Issue #867, 기준
`origin/develop`, feature branch `feat/issue-867-leader-audit-export`.

이 체크리스트는 `bluetape-workflow`의 checklist contract와
`bluetape-full-feature`의 A-01~A-12를 적용한다. `[ ]`는 아직 증명하지 않은
상태이며, `PENDING`은 외부 결과를 기다리는 상태다. 모든 증거는 현재
worktree와 현재 head에서 다시 읽는다.

## Type A mainline

- [x] **A-01 — 요구사항과 worktree 격리**
  - **Action:** Issue #867의 범위·제외사항을 확인하고 feature worktree에서 작업한다.
  - **Evidence:** repo=`bluetape4k-workshop`, base/head=`origin/develop`/`985beb08a0e16bec92dcd68d17bdb7a2e2b2ffc1`, worktree=`.worktrees/feat/issue-867-leader-audit-export`, issue=`https://github.com/bluetape4k/bluetape4k-workshop/issues/867`, unrelated dirty worktree preserved.
  - **Failure:** 관련 없는 변경을 보존하고 scope를 복구할 때까지 중지한다.
- [x] **A-02 — 현재 근거와 upstream 계약**
  - **Action:** GNO/live GitHub, 현재 workshop 구현, upstream 2.0.0-SNAPSHOT API를 교차 확인한다.
  - **Evidence:** local `JobSafetyConfiguration`/`JobSafetyProperties`/controller, GNO/live Issue #867, upstream leader issue #535 and merged PR #792 (`440e4f4e65a88eefdb822a5f3c1a7d44cd104046`), versionless catalog/BOM and public audit API reuse decisions.
  - **Failure:** 근거가 없는 설계·구현을 중지한다.
- [x] **A-03 — 설계 spec 승인·리뷰**
  - **Action:** brainstorming/writer로 spec을 작성하고 여섯 관점과 통합 리뷰를 통과시킨다.
  - **Evidence:** `docs/superpowers/specs/2026-08-30-issue-867-leader-audit-export-design.md`, `docs/review/2026-08-30-issue-867-spec-review.md`, SPW-01~05 PASS, six perspectives plus main integration, final P0=0/P1=0.
  - **Failure:** spec을 수정하고 영향받은 review를 재실행한다.
- [x] **A-04 — 구현 plan 승인·리뷰**
  - **Action:** 실행 가능한 plan을 작성하고 여섯 관점과 통합 리뷰를 통과시킨다.
  - **Evidence:** `docs/superpowers/plans/2026-08-30-issue-867-leader-audit-export-plan.md`, `docs/review/2026-08-30-issue-867-plan-review.md`, SPW-01~05, upstream/local traceability, latest six-perspective P0=0/P1=0.
  - **Failure:** 누락된 순서·검증·rollback을 보완한다.
- [x] **A-05 — 위험 예측**
  - **Action:** HTTP trust, retry/backpressure, cancellation, shutdown/resource 위험과 신호·완화·재실행 지점을 기록한다.
  - **Evidence:** plan Step 3-P `RISK-01~04`, Task 5 aggregate shutdown coordinator, trusted HTTPS/header gates, retry/drop signals, rollback/rerun points.
  - **Failure:** 위험 항목이 완성될 때까지 구현을 시작하지 않는다.
- [x] **A-06 — TDD 구현**
  - **Action:** 각 동작에 대해 RED/GREEN/REFACTOR를 순서대로 수행하고 Kotlin 규칙을 적용한다.
  - **Evidence:** delegated properties/payload/exporter lanes의 초기 계약 부재 실패 보고 후
    `:leader-job-safety-lab:test` targeted audit suite가 `BUILD SUCCESSFUL`로 전환됐고,
    report snapshot 일관성·Spring context restart 종료 assertion을 추가한 뒤 관련 테스트를
    재실행했다. 현재 diff는 audit package/config/web/test/docs의 scoped change다.
  - **Failure:** 기대한 원인으로 실패하지 않으면 테스트·설계를 먼저 고친다.
- [x] **A-07 — 테스트와 저장소 위험 검증**
  - **Action:** targeted test, module check, detekt, diff/readme/workflow/stale checks를 실행한다.
  - **Evidence:** module unit 88 tests/0 failed/0 skipped, serialized integration 12 tests/0
    failed/0 skipped, `:leader-job-safety-lab:check` 및 `scripts/smoke-validate.sh leader-full`
    PASS. `node scripts/validate-job-safety-lab-readme.mjs`, `git diff --check`,
    `scripts/smoke-validate.sh stale-check`(131 modules, stale 0, registration missing 0,
    broken image 0) PASS. root task graph에 `detekt` task가 없어 detekt는 N/A로 기록했다.
  - **Failure:** 실패 원인을 진단하고 Step 4로 되돌아간다.
- [x] **A-08 — pre-PR review 수렴**
  - **Action:** 최종 Kotlin checklist와 여섯 code-review 관점을 실행하고 P0/P1을 제거한다.
  - **Evidence:** `docs/review/2026-08-30-issue-867-pre-pr.md`의 API/Ops/Stability/Performance/Security/User
    여섯 관점과 현재 diff read-back, SPW-01~05, P0=0/P1=0/P2=0/P3=0. 기준 head는
    다음 Lore commit으로 고정한다.
  - **Failure:** PR 생성과 push를 막고 수정·재검증한다.
- [x] **A-09 — lesson commit**
  - **Action:** 재발 방지 lesson을 작성하고 Lore 형식으로 commit한다.
  - **Evidence:** `docs/lessons/2026-08-30-issue-867-leader-audit-export.md`와
    `docs/lessons/README.md` index를 작성했고, 최종 구현 commit에 Lore trailers를
    포함한다.
  - **Failure:** lesson을 보완할 때까지 PR을 만들지 않는다.
- [ ] **A-10 — PR·CI delivery**
  - **Action:** PR authority를 확인하고 exact head를 push·생성한 뒤 live metadata와 CI를 읽는다.
  - **Evidence:** CG-11~CG-14, PR URL/head, Korean body ending `## DoD Status`, checks.
  - **Failure:** stale/failed/pending evidence를 명시하고 downstream을 중지한다.
- [ ] **A-11 — merge-ready report**
  - **Action:** knowledge capture와 phase-aware DoD counts를 보고하고 merge approval 직전에 멈춘다.
  - **Evidence:** `Required checks: X/Y; N/A: N; Blocked: 0`, exact PR/head, unchecked CG-16~18.
  - **Failure:** 누락된 증거를 보완한다.
- [ ] **A-12 — 승인된 merge closeout**
  - **Action:** exact head에 대한 fresh `승인` 뒤 merge·live verification·local sync·cleanup을 수행한다.
  - **Evidence:** approval, merge SHA, parity, preserved/removed worktrees.
  - **Failure:** approval 전에는 `PENDING`으로 유지한다.

## Common gates

- [x] **CL-01 — mutation 전 checklist 생성**
  - **Action:** 위 mainline과 common CG rows의 적용 여부를 기록한다.
  - **Evidence:** `docs/review/issue-867-workflow-checklist.md`를 첫 작업 산출물로 생성하고 A-01~A-12, CL-01~CL-08, HAZ/RISK rows를 ordered로 기록했다.
  - **Failure:** checklist를 복구하기 전 mutation을 중지한다.
- [x] **CL-02 — 모든 항목 분류**
  - **Action:** required/conditional/N/A를 구체적 근거와 함께 표시한다.
  - **Evidence:** A-01~A-12와 CL rows는 required, HAZ-MOD/CAT/HTTP/WORKFLOW와 RISK rows는 triggered required로 분류했다. 새 module이 없다는 N/A는 registration chain을 확인한 뒤 기록한다.
  - **Failure:** 미분류 항목은 required unchecked로 취급한다.
- [x] **CL-03 — 의존성 순서 준수**
  - **Action:** prerequisite가 PASS인 뒤 dependent row를 실행한다.
  - **Evidence:** workflow/spec review completed before plan; implementation remains blocked until plan review and risk prediction pass.
  - **Failure:** affected downstream proof를 다시 실행한다.
- [x] **CL-04 — 증거 즉시 기록**
  - **Action:** 각 checked row 옆에 command/file/URL/result를 기록한다.
  - **Evidence:** spec, integrated spec review, current `git diff --check`, placeholder scan, and lane verdicts are recorded in this checklist/review artifact.
  - **Failure:** 재구성된 증거는 PASS로 인정하지 않는다.
- [x] **CL-05 — fail closed**
  - **Action:** pending/failed row의 dependent를 진행하지 않는다.
  - **Evidence:** initial P1 findings blocked plan; checked arithmetic, payload-store contract, and upstream raw-identity boundary repairs were applied and affected lanes rerun before marking A-03 PASS.
  - **Failure:** 계속 진행했다면 affected work를 재검증한다.
- [x] **CL-06 — 누락·순서 오류 repair**
  - **Action:** 빠진 gate를 복구하고 dependent를 재실행한다.
  - **Evidence:** plan/spec repair sequence: checked Long arithmetic and byte budget, transport-independent recording, local meter catalog, scope ownership, single shutdown coordinator, callback preemptive timeout, scheduler cancellation queue, transport parity and upstream warning-log boundary; API/Ops/Stability lanes rerun to P0/P1=0.
  - **Failure:** status remains BLOCKED.
- [ ] **CL-07 — irreversible hold refresh**
  - **Action:** PR/merge 전 target, head, authority, CI/review를 다시 읽는다.
  - **Evidence:** fresh live GitHub read-back.
  - **Failure:** side effect를 실행하지 않는다.
- [ ] **CL-08 — completion count**
  - **Action:** Required/N/A/Blocked와 unchecked IDs를 산출한다.
  - **Evidence:** final DoD report.
  - **Failure:** count가 reconcile될 때까지 completion claim을 하지 않는다.

## Conditional hazard branches

- [x] **HAZ-MOD-01 — module registration**
  - **Action:** 새 module이 없음을 확인하고 existing module registration chain을 검사한다.
  - **Evidence:** 새 module 없이 기존 `leader/job-safety-lab`을 확장했다. `gradlew projects`의
    131 project graph와 stale-check required registration PASS, coverage matrix/Examples/smoke
    path read-back PASS.
  - **Failure:** 누락된 registration을 보완한다.
- [x] **HAZ-CAT-01 — BOM/catalog**
  - **Action:** 새 alias가 versionless이고 root dependencies BOM이 유일한 authority인지 확인한다.
  - **Evidence:** `gradle/libs.versions.toml`의 leader/Jackson aliases에 version이 없고,
    root `build.gradle.kts`가 `platform(rootLibs.bluetape4k.dependencies)`를 import한다.
    module은 `implementation(libs.bluetape4k.jackson3)`만 추가했으며 개별 BOM/버전을 pin하지
    않는다.
  - **Failure:** 직접 버전 고정을 제거한다.
- [x] **HAZ-HTTP-01 — HTTP/HTTPS lifecycle**
  - **Action:** trusted HTTPS validation, allow-listed headers, timeout, status, cancellation, close를 테스트한다.
  - **Evidence:** `JobSafetyAuditPropertiesTest`의 HTTPS URI/host/header fail-closed,
    `JobSafetyAuditExporterTest`의 429 retry/400 terminal/cancel/close, `InMemoryAuditHttpClient`
    의 Java 25 bounded lifecycle, `JobSafetyAuditShutdownCoordinatorTest`의 종료 순서와
    `JobSafetyContextRestartIntegrationTest`의 실제 bean termination PASS.
  - **Failure:** trust/lifecycle gap을 보완한다.
- [x] **HAZ-WORKFLOW-01 — workflow/stale coverage**
  - **Action:** leader job-safety path가 Examples/smoke/stale checks에 등록됐는지 확인한다.
  - **Evidence:** `.github/workflows/Examples.yml`의 leader path filter/test/artifact,
    `scripts/smoke-validate.sh`의 `leader-full`/`all-smoke`, stale-check 및 README validator
    를 read-back했고 helper 실행 결과가 PASS했다. workflow 등록 변경은 불필요했다.
    초기 PR #899의 ecosystem gate가 기존 manifest track 부재로 `found 0`을 반환한 뒤,
    fixed track을 건드리지 않는 `issue-867-leader-audit` follow-up scope와 fresh
    `coordinator_scope_receipt`를 추가해 exact PR 경로를 등록했다.
  - **Failure:** registration을 보완하고 workflow를 재검증한다.

## Step 3-P risk prediction

- [x] **RISK-01 — bounded queue/backpressure**
  - **Signal:** `DROPPED_QUEUE_FULL`, queue depth, admission count.
  - **Mitigation:** upstream bounded exporter options with small deterministic test capacity.
  - **Rollback/rerun:** default transport remains local memory fake; rerun exporter tests serially.
  - **Evidence:** `JobSafetyAuditExporterTest` queue capacity 1에서 `DROPPED_QUEUE_FULL`,
    snapshot admission/queue 상태와 bounded payload store 테스트 PASS.
- [x] **RISK-02 — HTTPS trust and secret leakage**
  - **Signal:** non-HTTPS endpoint, disallowed header, raw token/customer/exception in payload.
  - **Mitigation:** `LeaderAuditTrustedHttpsEndpoint`, default sanitizer, allow-listed headers, redaction assertions.
  - **Rollback/rerun:** omit endpoint and use memory transport; rerun security tests.
  - **Evidence:** properties exact allow-list/URI rejection, authorization redaction, encoder
    raw identity/exception exclusion 및 operator security tests PASS.
- [x] **RISK-03 — context shutdown/cancellation**
  - **Signal:** non-zero queue/in-flight after close, unclosed executor, swallowed cancellation.
  - **Mitigation:** Spring dependency ownership, upstream close contract, cancellation tests.
  - **Rollback/rerun:** close exporter before executors and run fresh module check.
  - **Evidence:** shutdown coordinator trace가 subscription→exporter→client→scheduler→executor→scope
    순서로 한 번 실행되고, context restart 뒤 queue/in-flight/scheduledRetries=0 및 resources
    terminated를 확인했다.
- [x] **RISK-04 — local history semantics**
  - **Signal:** export sink returns no key or changes PostgreSQL authority.
  - **Mitigation:** admission-only bounded sink documented as non-authoritative; existing JDBC execution remains unchanged.
  - **Rollback/rerun:** remove sink wiring while retaining existing job-safety persistence paths.
  - **Evidence:** `AdmissionOnlyLeaderHistorySink`는 key admission 외 저장을 하지 않고,
    end-to-end test가 PostgreSQL resource fence/summary authority 불변을 확인했다.

# Issue #868 lease-extension observation workflow checklist

대상: `bluetape4k-workshop/leader/job-safety-lab`, Issue #868, stacked parent
`feat/issue-867-leader-audit-export` (`b557b0b231ec4d7317d42efb3b0b3c98b7befcbb`).

`[ ]`는 아직 fresh evidence가 없는 상태다. 구현·PR·merge gate를 서로 섞지 않고,
각 checked 항목에는 command/file/URL 근거를 남긴다.

## Type A mainline

- [x] **A-01 — 요구사항과 worktree 격리**
  - **Evidence:** live Issue #868, worktree `.worktrees/feat/issue-868-lease-extension`,
    #867 parent head 유지, unrelated dirty worktree 보존.
- [x] **A-02 — 현재 근거와 upstream 계약**
  - **Evidence:** GNO `bluetape4k-github`/`bluetape4k-docs` search, upstream
    `LeaderLeaseExtension*` public API와 Micrometer/Spring tests, local
    `JobSafetyConfiguration`/`JobRunCoordinator`/`RedisLeaderElectionAdapter`.
- [x] **A-03 — 설계 spec review**
  - **Action:** 실제 owner-thread `LockExtender` bridge, cancellation/no-event,
    upstream Spring prefix와 consumer prefix 경계를 여섯 관점으로 재검토한다.
  - **Evidence:** `docs/superpowers/specs/2026-08-31-issue-868-lease-extension-observation-design.md`,
    `docs/review/2026-08-31-issue-868-spec-review.md`, six perspectives P0/P1=0.
  - **Failure:** caller scope만 복사하거나 synthetic event를 사용하면 P1로 되돌리고 구현을 시작하지 않는다.
- [x] **A-04 — 구현 plan review**
  - **Action:** plan의 파일 책임, test order, bounded queue, single-context registration,
    cross-context 위임이 spec과 일치하는지 확인한다.
  - **Evidence:** `docs/superpowers/plans/2026-08-31-issue-868-lease-extension-observation-plan.md`,
    `docs/review/2026-08-31-issue-868-plan-review.md`, six perspectives P0/P1=0.
  - **Failure:** upstream 내부 manager 복제, unbounded wait, actual user event 미검증이면 수정 후 재검토한다.
- [x] **A-05 — 위험 예측**
  - **Action:** scope/thread-affinity, duplicate registration, NOOP/disabled leak,
    cancellation semantics, identity redaction, close/resource risks의 결정 신호와 대응을
    plan에 고정한다.
  - **Evidence:** plan의 위험 표와 checklist의 HAZ 항목.
  - **Failure:** 신호 또는 대응이 빠지면 구현 전에 보완한다.
- [x] **A-06 — TDD 구현**
  - **Evidence:** properties/registration/owner-thread proxy RED/GREEN 후 targeted unit
    20 passing; actual Redis integration은 `Extended` user/watchdog observation까지 확인.
- [x] **A-07 — 테스트와 저장소 위험 검증**
  - **Evidence:** `:leader-job-safety-lab:test` 99 passing, `:leader-job-safety-lab:integrationTest`
    13 passing, `:leader-job-safety-lab:check` BUILD SUCCESSFUL; README validator,
    `leader-full`, and `stale-check` helpers all PASS.
- [x] **A-08 — pre-PR review 수렴**
  - **Evidence:** `docs/review/2026-08-31-issue-868-pre-pr.md`, six perspectives
    P0/P1/P2=0.
- [ ] **A-09 — lesson commit**
  - **Evidence:** Korean lesson/index와 Lore commit.
- [ ] **A-10 — PR·CI delivery**
  - **Evidence:** stacked PR URL/head, live metadata, checks.
- [ ] **A-11 — merge-ready report**
  - **Evidence:** phase-aware DoD count와 unchecked IDs; merge hold.
- [ ] **A-12 — 승인된 merge closeout**
  - **Evidence:** fresh exact-head `승인`, merge SHA, sync/parity/cleanup.

## Common gates

- [x] **CL-01 — mutation 전 checklist 생성**
- [x] **CL-02 — 모든 항목 분류**
- [x] **CL-03 — 의존성 순서 준수**
  - **Evidence:** docs review가 P1=0이 된 뒤에만 implementation lane으로 이동한다.
- [x] **CL-04 — 증거 즉시 기록**
  - **Evidence:** spec/plan/pre-PR review와 lesson에 targeted unit, real Redis
    integration, Docker prerequisite, manifest PASS 결과를 기록했다.
- [x] **CL-05 — fail closed**
  - **Evidence:** thread-affinity 또는 observation contract가 불명확하면 구현/PR을 멈추는 규칙.
- [x] **CL-06 — 누락·순서 오류 repair**
  - **Evidence:** 초기 review의 caller handle, manager clone, cancellation/elapsed, option SSOT,
    checklist 순서 오류와 released snapshot ABI 차이를 spec/plan/checklist에 반영했다.
- [ ] **CL-07 — irreversible hold refresh**
- [ ] **CL-08 — completion count**

## Conditional hazards

- [x] **HAZ-MOD-01 — 기존 module 확장**: 새 module 없음; existing registration chain 유지.
- [x] **HAZ-CAT-01 — BOM/catalog**: 새 dependency pin 없이 existing versionless
  root BOM을 유지했다.
- [x] **HAZ-OBS-01 — global registration/NOOP**: 단일 context registration count, idempotent
  close, NOOP/disabled callback absence 검증. cross-context scope는 artifact 갱신 후 upstream에 위임.
- [x] **HAZ-CONC-01 — owner thread bridge**: owner executor source별 event 및 actual
  `LockExtender` `Extended` 검증.
- [x] **HAZ-LIFE-01 — context close**: `JobSafetyContextRestartIntegrationTest`에서
  registration close와 Redis lease release를 검증하고 전체 integration 13건을 통과했다.
- [x] **HAZ-WORKFLOW-01 — workflow/stale coverage**: `scripts/smoke-validate.sh leader-full`
  및 `scripts/smoke-validate.sh stale-check` fresh output PASS, `actionlint` PASS.

## Delivery hold

- PR target은 stacked parent head로 고정하고, PR body는 한국어 `[2.0.0]` 제목·milestone
  `2.0.0`·DoD status를 사용한다.
- fresh exact-head 승인 전 merge/auto-merge하지 않는다.

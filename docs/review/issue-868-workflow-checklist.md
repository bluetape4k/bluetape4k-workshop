# Issue #868 lease-extension observation workflow checklist

대상: `bluetape4k-workshop/leader/job-safety-lab`, Issue #868, stacked parent
`feat/issue-867-leader-audit-export` (`6bec68ff043bb262c930fc01a86729cb675d8e08`).

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
- [ ] **A-03 — 설계 spec review**
  - **Evidence:** `docs/superpowers/specs/2026-08-31-issue-868-lease-extension-observation-design.md`,
    `docs/review/2026-08-31-issue-868-spec-review.md`, six perspectives P0/P1=0.
- [ ] **A-04 — 구현 plan review**
  - **Evidence:** `docs/superpowers/plans/2026-08-31-issue-868-lease-extension-observation-plan.md`,
    `docs/review/2026-08-31-issue-868-plan-review.md`, six perspectives P0/P1=0.
- [x] **A-05 — 위험 예측**
  - **Evidence:** plan의 scope 누락, duplicate registration, NOOP/disabled leak,
    identity redaction, close/resource risks table.
- [ ] **A-06 — TDD 구현**
  - **Evidence:** RED/GREEN commands와 fresh test output.
- [ ] **A-07 — 테스트와 저장소 위험 검증**
  - **Evidence:** module tests, integration, README/workflow/stale helpers.
- [ ] **A-08 — pre-PR review 수렴**
  - **Evidence:** final Kotlin/API/Ops/Stability/Performance/Security/User review,
    P0/P1=0.
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
- [x] **CL-04 — 증거 즉시 기록**
- [x] **CL-05 — fail closed**
- [ ] **CL-06 — 누락·순서 오류 repair**
- [ ] **CL-07 — irreversible hold refresh**
- [ ] **CL-08 — completion count**

## Conditional hazards

- [x] **HAZ-MOD-01 — 기존 module 확장**: 새 module 없음; existing registration chain 유지.
- [ ] **HAZ-CAT-01 — BOM/catalog**: 변경 시 versionless alias와 root BOM read-back.
- [ ] **HAZ-OBS-01 — registry identity/NOOP**: registration count, option mismatch,
  callback duplicate/absence 검증.
- [ ] **HAZ-CONC-01 — scope 전파**: coordinator와 executor thread source별 event 검증.
- [ ] **HAZ-LIFE-01 — context close**: scope inactive, lease release, worker 종료 검증.
- [ ] **HAZ-WORKFLOW-01 — workflow/stale coverage**: helper fresh output.

## Delivery hold

- PR target은 stacked parent head로 고정하고, PR body는 한국어 `[2.0.0]` 제목·milestone
  `2.0.0`·DoD status를 사용한다.
- fresh exact-head 승인 전 merge/auto-merge하지 않는다.

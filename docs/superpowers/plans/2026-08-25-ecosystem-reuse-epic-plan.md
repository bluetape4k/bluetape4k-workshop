# bluetape4k ecosystem 재사용 Epic 실행 계획

> **작업자 안내:** 이 계획은 작업을 단계별로 실행하는 기준이다. 각 단계는
> checkbox(`- [ ]`)로 추적하고, 코드·테스트 변경 전에는
> `$bluetape-kotlin-patterns`와 `$test-driven-development`를 적용한다.

**목표:** #792를 live Epic으로 승격하고 등록된 ecosystem 재사용 및
`bluetape4k-assertions` 지적 사항을 의존성이 정확한 hybrid stacked PR train으로
실행한다.

**구조:** `P0`는 `origin/develop` 기반의 문서·검증 계약 foundation이다. Field
Service와 serialization/fixture 변경은 실제 consumer boundary를 공유할 때만 쌓고,
HTTP·Testcontainers/R2DBC·runtime track은 `P0`에서 분기해 독립적으로 검토한다.
모든 child PR은 capability inventory, raw-fallback 분류, 7-Tier review,
targeted proof를 제출한다.

**기술 스택:** Kotlin 2.4.0, Java 25, Spring Boot 4.0.6, Gradle,
`bluetape4k-dependencies` BOM, JUnit 5, `bluetape4k-assertions`, 기존 mocking
용도의 MockK, Testcontainers, `detekt`, GitHub CLI. Kluent는 이미 변경하지 않는
legacy assertion block에만 허용하며, touched assertion은 inventory 사유 없이
`bluetape4k-assertions` 우선 규칙을 따른다.

## 2026-08-27 실행 갱신

이 문서는 2026-08-25 승인 설계를 현재 `develop` 기준으로 이어가는 실행 계획이다.
P0/A1은 이미 `develop`에 통합되었고, 삭제된 historical foundation ref를
child base로 재사용하지 않는다. 현재 coordinator branch는
`fix/ecosystem-reuse-train-replan`이며, F1/P2-02, A2, R1, T1, I1은 최신
`develop`에서 exact base/head를 다시 고정한다. 사용자의 최신 병합 지시는
`squash`가 아닌 `rebase merge`이므로 모든 PR delivery와 closeout은 이 전략만
허용한다. rebase 후에는 implementation marker, manifest receipt, targeted
test, CI, review를 새 exact head에서 다시 검증하며 이전 green 결과를
재사용하지 않는다.

---

## 실행 계약

- 저장소: `bluetape4k/bluetape4k-workshop`
- 기준: 계획 시점의 `origin/develop`; PR 또는 stack 전환마다 최신 상태를 다시 확인한다.
- Foundation history: 이미 `develop`에 통합된 historical `feat/ecosystem-reuse-gate`; 현재 작업은 `fix/ecosystem-reuse-train-replan`에서 stale ref를 수리한다.
- 공개 문서: 한국어로 작성하고 code, path, API name, command, URL, exact error는 보존한다.
- 의존성 규칙: Bluetape 버전은 `platform(libs.bluetape4k.dependencies)`만 관리한다.
- 병합 규칙: `squash`와 auto-merge는 금지하고, 각 PR은 exact head에 대한 fresh 승인 후 `rebase merge`로만 통합한다. 승인 전에는 merge-ready 증거에서 멈춘다.
- 무관한 dirty 변경: main checkout의 `.github/workflows/Examples.yml` 수정은 보존하며, train 범위에 속한다는 증거 없이는 이 worktree로 복사하지 않는다.

GitHub read-only commands use a bounded wrapper with a 30-second deadline, one
read-only retry, and a receipt containing command name, exit code, elapsed time,
and observed `updatedAt`/head OID. Mutation commands have no automatic retry.

모든 child test receipt는 동일한 구조를 사용한다: `command`,
`gradle_tasks`, `test_selectors`, `gradle_flags`, `timeout_seconds`,
`docker_required`, `cache_mode=no-build-cache`, `max_workers=1`, `parallel=false`,
시작/종료 timestamp, elapsed milliseconds, exact-head OID와 manifest checksum,
Gradle task count, test pass/fail/skipped/disabled counts, 해당 시 container
IDs/status, cleanup 결과, artifact paths. 또한 각 Bluetape capability마다
`resolved_dependency_receipt`를 포함한다. 이 receipt는 해당 모듈의
`dependencyInsight` 또는 동등한 Gradle resolved-dependency 출력 경로,
resolved coordinate, BOM source, 그리고 실제 import/API anchor를 기록해야
한다. checked-in manifest의 `dependency_insight_commands`는 각 track의
정확한 `dependencyInsight --dependency ... --configuration ...` 명령을
고정하며, receipt의 출력 경로와 resolved coordinate/BOM source가 이 명령과
일치해야 한다. 누락되거나 선언과 resolved coordinate가 다르면 child를 `PENDING`으로
남긴다. `command`는 설명용 receipt data이며 shell에서 재실행하지 않는다.

receipt는 `.bluetape/receipts/TRACK/RECEIPT_ID.json.tmp`에 먼저 쓰고
`fsync` 후 같은 디렉터리의 최종 JSON으로 atomic rename한다. 각 receipt에는
`termination_reason`(`completed`, `signal`, `timeout`, `cancelled`,
`cleanup-failure`), `exit_code`, signal, runner, Git/Gradle/Java/Docker version,
selector별 결과를 추가한다. `cancel-in-progress` 또는 timeout 중에도
`if: always()` artifact 단계가 마지막으로 sanitized receipt와 resource ID를
보존하며, credential·token·전체 request body는 기록하지 않는다. artifact
retention은 7일로 제한한다.

실행 상태와 receipt 상태는 분리한다. `state`는 `PLANNED/READY/INVALID/
MERGE_READY/MERGED`만 사용하고, timeout·cancel·cleanup failure는
`receipt_status=TIMEOUT/CANCELLED/CLEANUP_FAILED`와 `state=PLANNED`를 남긴다.
재실행에서 새 receipt가 PASS가 된 뒤에만 coordinator가 READY로 전환한다.
manifest의 `receipt_transitions`는 초기 `PENDING`, terminal
`PASS/FAIL/CANCELLED/TIMEOUT/CLEANUP_FAILED`, 그리고 실패·중단 후
`PENDING` 재실행만 허용하는 전이표다. `gradle_tasks`는 실행할 task path이고
`test_selectors`는 task 또는 `--tests` pattern이며, receipt 결과 key는
각 필드와 동일해야 한다. terminal receipt JSON은 immutable artifact로 취급한다.
재실행은 기존 terminal 파일을 수정하지 않고 새 `receipt_id` 파일을 atomic하게
작성한 뒤 manifest node를 `state=PLANNED`, `receipt_status=PENDING`,
`receipt_id=null`, `checksum=null`로 되돌린다. 이전 receipt는 보존하며, 같은
terminal status에서 ID/checksum을 바꾸는 in-place mutation은 거부한다.

T1/I1의 canonical cross-process lock은
`/tmp/bluetape4k-workshop-ecosystem-reuse.lock`이며, lock directory 안에
owner receipt, PID, host, start timestamp를 기록한다. 획득 timeout은 30초,
stale 판정은 PID가 없고 start timestamp가 2시간을 초과할 때만 허용한다.
활성 PID의 lock은 삭제하지 않는다. Docker task-owned label은
`org.bluetape4k.ecosystem-reuse=true`, `org.bluetape4k.train-track=TRACK`,
`org.bluetape4k.receipt-id=RECEIPT_ID`로 고정하며, cleanup은 receipt에 기록된
검증된 ID에 한해 `docker rm -f`를 수행하고 broad prune을 실행하지 않는다.

## 파일과 소유권

| Area | Files | Owner/track |
|---|---|---|
| Epic design/plan | `docs/superpowers/specs/2026-08-25-ecosystem-reuse-epic-design.md`, `docs/superpowers/plans/2026-08-25-ecosystem-reuse-epic-plan.md` | `P0` |
| Coverage contract | `docs/coverage-matrix.md`, `docs/ecosystem-reuse-inventory.md` | `P0` |
| Train manifest | `docs/ecosystem-reuse-train.json` | coordinator/serial closeout only |
| CI/report gate | `.github/workflows/ecosystem-reuse-gate.yml`, `.github/scripts/check-ecosystem-reuse.py`, `.github/scripts/test_check_ecosystem_reuse.py`, `docs/governance/github-action-pins.json` | `P0`; no child edits these files without an explicit conflict note |
| Assertion migration | module-local `src/test/kotlin/**` and module `build.gradle.kts` | `A1`/`A2` |
| Field Service contracts | `optimization/field-service-dispatch/**` | `F1`/`F2` |
| JSON/HTTP/fixtures | `commerce/usage-metering-billing-event-sourcing/**`, `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/api/**` (worker 제외), `shared/src/main/kotlin/io/bluetape4k/workshop/shared/web/**`, `commerce/pre-generated-voucher-pool/**`, `commerce/concert-ticket-flash-sale/**` | `R1`/`R2`; exact leaf paths are manifest-owned |
| Testcontainers/R2DBC | `spring-data/r2dbc-coroutines/**`, affected `build.gradle.kts` | `T1` |
| Fencing/runtime/Money/launcher | `leader/job-safety-lab/**`, `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/worker/**`, `commerce/usage-metering-billing-ledger/**` | `I1`; exact leaf paths are manifest-owned |
| Child 7-Tier receipt | `docs/review/DATE-TRACK-7tier.md` | 해당 child; manifest의 `review_artifact`와 allowlist에 고정 |

활성 lane 두 개가 같은 파일을 편집할 수 없다. checker는 directory allowlist가
file allowlist를 포함하는 경우까지 포함해 active node 사이의 path containment
overlap을 거부한다. child issue가 표의 경계를 넘으면 코딩 전에 분리하고,
겹침을 숨기지 말고 Epic train 표를 갱신한다. 커밋된
`docs/ecosystem-reuse-train.json`이 유일한 train manifest이며, workflow
coordinator와 serial closeout lane만 이를 갱신할 수 있다.

## Task 1: Confirm foundation state and record the continuation worktrees

**Files:**
- Create: `docs/ecosystem-reuse-train.json` through the coordinator-owned bootstrap step
- Read-only: repository, worktree, and GitHub state

- [ ] **Step 1: Read live repository and issue state**

  Run:

  ```bash
  git fetch origin develop
  git status --short --branch
  git rev-parse origin/develop
  gh issue view 792 --json number,title,state,labels,milestone,body,updatedAt,url
  gh issue list --state open --limit 100 --search "milestone:1.4.0"
  ```

  Expected evidence: current `origin/develop`, dirty-file preservation, #792
  metadata, and all registered child issue numbers. Do not edit the main checkout.

  Before any new GitHub mutation, inspect unfinished workflow receipts under the
  worktree state root. Read back every prior issue/comment mutation and classify it
  as `applied`, `not-applied`, or `unknown`; an `unknown` receipt blocks new writes
  until a complete live readback resolves it. Read-only GitHub commands have a
  bounded deadline and may be retried once; mutation commands are never blindly
  retried.

- [x] **Step 2: Confirm the isolated worktree**

  The historical `.worktrees/feat/ecosystem-reuse-gate` foundation was already
  integrated into `develop` and its branch was deleted. Do not recreate or alias
  that ref. Instead, preserve the live dirty-file audit and use the continuation
  worktrees recorded in the 2026-08-27 execution receipt:

  ```bash
  git worktree list --porcelain
  git status --porcelain=v1 -z
  git -C .worktrees/fix/ecosystem-reuse-f1 rev-parse HEAD
  git -C .worktrees/test/ecosystem-reuse-assertions-platform rev-parse HEAD
  ```

  Expected evidence: current `origin/develop` is the base for new independent
  children, F1 is rebased onto that head, and no historical worktree is deleted
  or recreated.

- [ ] **Step 3: Initialize the machine-readable train manifest**

  Create `docs/ecosystem-reuse-train.json` with one node for `P0`, `A1`, `A2`,
  `F1`, `F2`, `R1`, `R2`, `T1`, and `I1`. Each node must contain `track`,
  `expected_head_ref`, `expected_base_ref`, `parent_track`, `oid_policy`, `head_oid`,
  `base_oid`, `parent_oid`, `merge_base_oid`, `state` (`PLANNED`, `READY`,
  `INVALID`, `MERGE_READY`, or `MERGED`), `issue_numbers`, `allowed_paths`,
  `gradle_tasks`, `test_selectors`, `gradle_flags`, `timeout_seconds`,
  `docker_required`, `review_artifact`, `dependency_insight_commands`,
  `receipt_id`, `receipt_status`, and `checksum`. The canonical schema above and
  its checker are authoritative for the complete transition tables, receipt
  fields, and R2 `parent_evidence`; this task list is not a reduced schema.
  Initialize the manifest with `oid_policy=reviewed-ancestor`,
  `reviewed_implementation_oid` and legacy OIDs as `null`, `state=PLANNED`, and
  a pending receipt. The review artifact marker must identify a reviewed
  implementation ancestor, and the bounded evidence tail may change only that
  artifact. The checker must reject a marker equal to the PR head, a non-ancestor,
  malformed marker, or code changes after the marker. Do not place this
  machine state under `.bluetape`; the workflow script remains the sole writer
  of `.bluetape` receipts.

## Task 2: Prepare the Epic and train mutation contract

**Files:**
- Modify: GitHub Issue #792 title, labels, body, and checklist
- Modify: GitHub child issue comments only where a train/base relationship is needed

- [ ] **Step 1: Render the #792 mutation preimage without mutating GitHub**

  Fetch the complete body and `{title, labels, milestone, updatedAt}` first. Compute
  a normalized body hash and save the complete preimage in the local workflow
  receipt. Validate the fixed owner/repository, issue number, label names, milestone,
  and branch names against the train manifest. Render the replacement to a checked
  body file or stdin; never interpolate GitHub body text into a shell command.
  Immediately before the later bounded mutation, re-read `updatedAt` and body hash
  and abort on drift. This task only renders and validates the replacement; it does
  not call `gh issue edit` or `gh issue comment`.
  Store the preimage/receipt with `umask 077` and mode `0600` under the local state
  root only; redact credential-like values, never print or commit the file, never
  upload it as a CI artifact, and delete it after readback/rollback unless the
  workflow retention policy explicitly requires the redacted receipt.
  The replacement must retain the evidence, classification, existing child rationale,
  and final `## DoD Status`, while adding:

  ```text
  Epic: bluetape4k ecosystem-first 재사용 및 assertions 정비 train
  labels: enhancement, Epic, difficulty:epic-program, refactoring, dependencies, area:governance
  milestone: 1.4.0
  ```

  Add checklist links for #777, #779, #781~#791, #793~#796, #798~#808;
  state explicitly that #797 is excluded; add the `P0/A1/A2/F1/F2/R1/R2/T1/I1`
  train table and the Epic acceptance criteria from the spec.

- [ ] **Step 2: Preserve the live preimage and readback contract**

  ```bash
  gh issue view 792 --json title,labels,milestone,body,updatedAt,url
  ```

  Save the full response, mutation receipt, body hash, and updated timestamp. If a
  partial mutation fails, forward-repair only the missing field after a fresh
  preimage check; do not blindly repeat the entire update.

  Expected evidence: the complete preimage and replacement body are available for
  the later readback. The live issue is still expected to be unchanged at this
  point; if it drifted, discard the rendered body and regenerate it.

- [ ] **Step 3: Prepare bounded train comments for the post-freeze mutation**

  Add one Korean comment per track root (`#783`, `#786`, `#777`, `#804`, `#798`,
  `#794`, `#779`, `#799`) with the planned branch and base. Do not change child
  issue status or close anything. Use a stable marker such as
  `<!-- ecosystem-reuse-train:v1:track=A1 -->`; record the current manifest checksum
  in a separate body field rather than changing the marker when the manifest changes.
  Read the current comment list and its hash first. Only a comment authored by the current trusted
  actor may be updated/skipped by marker; a foreign author using the marker is a
  conflict and must stop. Store the eight body files locally and defer the actual
  `gh issue comment` calls until the P0 exact-head freeze. Record comment ID, author,
  URL, body hash, and pre/post comment-list hash in the mutation receipt, then
  re-read all eight roots.

  Mutation recovery is field-specific: an issue body mismatch restores the complete
  saved body file; a label mismatch forward-repairs only the missing label; a
  milestone mismatch repairs only the milestone; a title mismatch repairs only the
  title; and a comment mismatch updates the single trusted stable-marker comment.
  Every repair re-reads the full `{title, labels, milestone, body, updatedAt}` and
  comment list. If any field or actor is ambiguous, stop permanently for this run;
  never infer success from a partial response.

## Task 3: Publish the P0 inventory and validation contract

**Files:**
- Modify: `docs/coverage-matrix.md`
- Create: `docs/ecosystem-reuse-inventory.md`
- Modify: `docs/ecosystem-reuse-train.json` through coordinator validation/bootstrap only
- Create: `docs/governance/github-action-pins.json`
- Create: `.github/scripts/check-ecosystem-reuse.py`
- Create: `.github/scripts/test_check_ecosystem_reuse.py`
- Create: `.github/workflows/ecosystem-reuse-gate.yml`

- [ ] **Step 1: Add the inventory fixture before the checker**

  Write one row per registered finding with these columns:

  ```text
  issue | module | capability | dependency_alias | resolved_module | actual_import | capability_api | source_anchor | test_anchor | bluetape_source_anchor | bluetape_test_anchor | classification | fallback_reason | status
  ```

  Every path anchor must exist except an explicit `N/A` `actual_import` for a
  `provider-gap`, `shared-candidate`, or `documented-raw-fallback` row. Such a
  row must use a `candidate:` `capability_api` marker and retain local
  `source_anchor` and `test_anchor`. Otherwise `actual_import` must be a
  source/test file containing the exact Bluetape import and the declared
  `capability_api` token. A Gradle build/catalog declaration and a `libs.*`
  alias are dependency-resolution evidence only; neither is adoption evidence.
  This prevents a generic `bluetape4k` substring from masquerading as reuse.
  `bluetape_source_anchor` and `bluetape_test_anchor` point to current
  repository comparison files for released capabilities; a non-released
  fallback classification may use `N/A` for those upstream anchors while its
  local `source_anchor` and `test_anchor` remain required. `resolved_module`
  must match an alias/module in `gradle/libs.versions.toml` and the root BOM
  contract. Use `status=pending` for child work that has not
  landed; `status=verified` only after the child PR supplies fresh test evidence
  and the issue's manifest node has `state=READY|MERGE_READY|MERGED`,
  `receipt_status=PASS`, non-empty `receipt_id`, and checksum.
  Classifications are exactly `released-bluetape4k`, `behavior-under-test`,
  `provider-gap`, `shared-candidate`, or `documented-raw-fallback`.

### Train manifest schema 경계

`docs/ecosystem-reuse-train.json`의 top-level 계약은 `schema_version=1`,
고정 9-track 목록, `state_values`, `receipt_status_values`,
`receipt_transitions`, `state_transitions`다. 각 node는 `expected_*_ref`,
`parent_track`, `oid_policy`, issue 목록, disjoint `allowed_paths`,
`gradle_tasks`, `test_selectors`, `gradle_flags`, `timeout_seconds`,
`docker_required`, `dependency_insight_commands`, `review_artifact`,
`reviewed_implementation_oid`, OID와 `receipt_id/status`, checksum을 가진다.
최초 P0 manifest에서 fixed node는 `oid_policy=reviewed-ancestor`,
`state=PLANNED`, reviewed/legacy OID `null`, receipt `PENDING`만 허용한다.
review artifact marker와 base→ancestor→head 이력 및 문서-only evidence tail을
검증하며, self-reference와 임의 SHA를 묵인하지 않는다.
`P0`는 자체 review artifact를 가지며, child는 `DATE-TRACK-7tier.md`를 자신의
allowlist에 포함한다. `R2`만 추가로
`parent_evidence`(`parent_track`, `r1_api_anchor`, `r1_allowed_path`,
`r2_consumer_anchor`, `r2_test_anchor`, `required=true`)를 가진다. checker는 이 top-level/node 필드를 exact
trusted-base 비교와 negative test 대상으로 취급한다.

- [ ] **Step 2: Write the checker test first**

  In `.github/scripts/test_check_ecosystem_reuse.py`, cover:

  1. a valid row with an existing source/test path;
  2. a missing required column;
  3. an unknown classification;
  4. a missing source anchor;
  5. a raw fallback without a non-empty reason;
  6. a duplicate issue/module key.
  7. an absolute path, `../` traversal, symlink, NUL/newline, or control character;
  8. a malicious issue/module/reason value that must be escaped in the report;
  9. a manifest whose `allowed_paths` or `test_selectors` differs from the trusted
     base manifest without an explicit coordinator state transition;
  10. a workflow action tag/branch reference, `persist-credentials: true`, or a
      secret/token environment handoff.
  11. missing `actual_import`, `capability_api`, `bluetape_source_anchor`,
      `bluetape_test_anchor`, or an unknown `resolved_module`;
  12. overlapping active-node `allowed_paths`, including directory/file
      containment;
  13. missing structured `gradle_tasks`, `gradle_flags`, `timeout_seconds`,
      `docker_required`, or an invalid `receipt_status` transition;
  14. a changed module build file that adds an individual Bluetape BOM/version
      pin instead of consuming `bluetape4k-dependencies`.
  15. an inventory issue mapped to zero or multiple train nodes; a dependency
      insight command missing the exact Gradle project, resolved coordinate, or
      configuration; duplicate or extra command keys; missing R2 anchor symbols;
      stale R1 evidence after an R2 reparent; an invalid state transition; or a
      terminal receipt ID/checksum mutation.
  16. a released row whose `actual_import` points at a Gradle/catalog file or
      whose `capability_api` is a `libs.*` alias; and a pull-request diff that
      escapes its single manifest node or uses the wrong base/head ref or OID.

  Run:

  ```bash
  python3 .github/scripts/test_check_ecosystem_reuse.py -v
  ```

  Expected result: the new tests fail because the checker is not implemented.

- [ ] **Step 3: Implement the smallest deterministic checker**

  `.github/scripts/check-ecosystem-reuse.py` must use only the Python standard
  library, accept `--inventory` (default `docs/ecosystem-reuse-inventory.md`) and
  an optional trusted manifest path, resolve paths from repository root, and exit
  non-zero when any of the following holds. It must reject any
  inventory/anchor path containing NUL, newline, other control characters,
  absolute paths, external symlinks, or a resolved path outside the repository.
  Use `Path.resolve()` followed by `relative_to(repo_root)` for containment and
  fail closed on errors:

  - a required column is absent or blank;
  - an issue/module key is duplicated;
  - a classification is outside the five allowed values;
  - a source/test anchor path does not exist;
  - any classification other than `released-bluetape4k` lacks a non-empty
    `fallback_reason`; `released-bluetape4k` may also record an explanatory
    reason, but does not require one.

  The output is a stable sorted report with `PASS`/`FAIL`, issue number, module,
  and reason. Escape control characters and newlines, exclude environment variables
  and credentials, upload the report only as a bounded failure artifact, and never
  feed the report to a shell step. It must not inspect generated build directories
  or mutate files. When a trusted manifest is supplied, compare its checksum,
  `allowed_paths`, `gradle_tasks`, `test_selectors`, `gradle_flags`,
  `timeout_seconds`, and `docker_required`; the `command` field in a receipt is
  data and is never executed. A change in those fields fails closed unless the coordinator has
  advanced the manifest state and receipt.

  The only exception is the P0 bootstrap: when the trusted base ref is
  `origin/develop` and has no manifest yet, `--bootstrap` may be passed to
  validate the exact schema and fixed nine-track allowlist (`P0`, `A1`, `A2`,
  `F1`, `F2`, `R1`, `R2`, `T1`, `I1`). After P0 merges, `--bootstrap` is
  forbidden and the trusted-base manifest is mandatory; any missing or widened
  field fails closed. It must also reject active-node path containment overlap,
  missing structured execution fields, a receipt transition that skips
  `PENDING`, and any changed module build file that pins an individual Bluetape
  BOM/version instead of `bluetape4k-dependencies`. For released capability rows,
  the checker verifies the declared import/API and resolved module anchor; any
  non-released fallback classification may use `N/A` for an upstream anchor, but
  its local `source_anchor`/`test_anchor` and written `fallback_reason` remain
  mandatory. Every inventory row must map to exactly one track, and every
  released or candidate dependency must have exactly one matching
  `dependencyInsight` project/coordinate/configuration key in that track's
  manifest; duplicate and extra keys fail closed.

- [ ] **Step 4: Run the checker tests to green**

  ```bash
  python3 .github/scripts/test_check_ecosystem_reuse.py -v
  python3 .github/scripts/check-ecosystem-reuse.py \
    --inventory docs/ecosystem-reuse-inventory.md \
    --manifest docs/ecosystem-reuse-train.json --bootstrap
  ```

  Expected result: all unit tests pass and the committed inventory returns `PASS`.

- [ ] **Step 5: Connect the checker to an isolated workflow**

  Create `.github/workflows/ecosystem-reuse-gate.yml` rather than extending the
  mutable-action surface of `Examples.yml`. Trigger it on `push`/`pull_request`
  paths for the inventory, manifest, checker, and this workflow, plus
  `workflow_dispatch`. Set workflow and job `permissions: contents: read`; do not
  grant `issues`, `pull-requests`, or `actions` write permissions. The checkout
  must set `persist-credentials: false`, use the read-only PR ref for forks, and
  receive no secrets or token environment. Every `uses:` in the new workflow must
  be a 40-character commit SHA recorded with its release tag and source URL in
  `docs/governance/github-action-pins.json`; add a static negative test that rejects
  tag/branch references and any token/secrets handoff. Compare the PR manifest
  checksum, `allowed_paths`, and `test_selectors` with the trusted manifest from the
  expected base ref; any unapproved widening or changed structured execution
  field fails the gate. Receipt command data is never executed. Upload only the
  sanitized failure report with bounded retention and never execute that report.
  The job must never imply that a skipped child module test passed. Set
  `timeout-minutes: 10`, use the concurrency group
  `ecosystem-reuse-${{ github.event.pull_request.number || github.ref }}` with
  `cancel-in-progress: true`, and retain failure artifacts for at most 7 days.
  A timeout or cancellation leaves the affected receipt `PENDING` rather than
  synthesizing a successful result.

- [ ] **Step 6: Extend the coverage matrix**

  Replace the blanket assertions `Good` claim with a dated Korean section linking
  to the inventory and the assertion train. Keep existing coverage rows intact;
  record raw assertion exceptions and `build-logic` scope separately.

## Task 4: P0 산출물 검토 및 커밋

**Files:** all Task 2/3 outputs.

- [ ] **Step 1: 문서 및 정적 검사 실행**

  ```bash
  git diff --check
  python3 -m unittest .github/scripts/test_check_ecosystem_reuse.py -v
  python3 .github/scripts/check-ecosystem-reuse.py \
    --inventory docs/ecosystem-reuse-inventory.md \
    --manifest docs/ecosystem-reuse-train.json --bootstrap
  /usr/bin/time -p ./gradlew --no-daemon --no-build-cache --max-workers=1 detekt
  ```

  `detekt`를 사용할 수 없거나 unrelated baseline 사유로 실패하면 exact output을
  기록하고 DoD를 `PENDING`으로 유지한다. PASS로 바꾸지 않는다. 반복되는 train
  head를 비교할 수 있도록 receipt에 command line, Gradle cache mode, elapsed
  time, task/test count, skipped/disabled count를 기록한다.

- [ ] **Step 2: 6개 관점의 P0 review 실행**

  exact diff를 performance, stability, security, operator/Ops, developer/API,
  user/caller 관점으로 검토한다. 통합 표는 P0=0 및 P1=0이어야 하며,
  P2/P3 finding은 수정하거나 child issue로 연결한다. 통합 한국어 review note에
  SPW-01~SPW-05를 적용한다.

- [ ] **Step 3: 승인된 P0 foundation 커밋**

  ```bash
  git add docs/superpowers/specs/2026-08-25-ecosystem-reuse-epic-design.md \
    docs/superpowers/plans/2026-08-25-ecosystem-reuse-epic-plan.md \
    docs/coverage-matrix.md docs/ecosystem-reuse-inventory.md \
    docs/ecosystem-reuse-train.json docs/governance/github-action-pins.json \
    .github/scripts/check-ecosystem-reuse.py \
    .github/scripts/test_check_ecosystem_reuse.py \
    .github/workflows/ecosystem-reuse-gate.yml \
    docs/review/2026-08-25-ecosystem-reuse-epic-design-plan-review.md
  git commit -m "예제의 ecosystem 재사용 기준을 Epic train으로 고정"
  ```

  commit message는 `AGENTS.md`의 Lore trailer를 사용해야 하며, commit에는
  P0 소유 파일만 포함한다.

- [ ] **Step 4: child 작업 전 P0 PR gate 동결**

  `P0_HEAD_OID`, base OID, merge-base, exact CI conclusion, review convergence,
  train manifest checksum을 기록한다. P0 PR이 exact-head gate를 통과하기 전에는
  child branch를 만들거나 전진시키지 않는다. 모든 child는 생성 시 parent OID를
  기록한다. 이후 P0가 바뀌면 모든 descendant를 `INVALID`로 표시하고, 예전의
  green check를 재사용하지 말고 topological order로 rebase/retest한다.

- [ ] **Step 5: P0 동결 후 제한된 Epic mutation 적용**

  Step 1–4가 모두 green인 경우에만 전체 #792 preimage와 8개 comment list를
  다시 읽는다. mutation 직전에 `updatedAt`과 normalized body hash를 비교한 뒤
  하나의 완전한 `gh issue edit --body-file`와 필요한 label/milestone 갱신을
  수행한다. Task 2의 stable comment marker를 사용하고 root마다 comment
  mutation을 한 번만 수행한다. 완전한 live readback 없는 mutation 재시도는
  금지한다. title, labels, milestone, 전체 body, `updatedAt`, 모든 child
  comment와 hash를 다시 읽는다. 부분 실패는 저장한 preimage를 기준으로 필드별
  forward repair를 수행한다. 결과가 `unknown`이면 완전한 live readback이
  `applied` 또는 `not-applied`를 증명할 때까지 train을 중지한다. mutation
  receipt에는 동결된 P0 head OID와 manifest checksum을 포함한다. 이 단계를
  실행하지 않으면 public Epic 상태는 `PENDING`으로 남긴다.

## Task 5: 의존성 순서에 따른 child track 실행

각 track은 동일한 TDD/review 계약을 반복한다. 커밋된 train manifest가 child
module file, 허용 command, write scope의 유일한 기준이다. Issue body는 설명용
metadata일 뿐이므로 외부 issue text로 path, shell fragment, module을 선택하지
않는다. 편집 전에 manifest와 issue number, expected module mapping,
repository-root containment를 대조한다. 모든 code task는 failing test로 시작해
최소 Kotlin 변경, module test, 7-Tier review 순서로 진행한다.

각 child는 `docs/review/DATE-TRACK-7tier.md`를 생성한다. 문서에는 track와
severity를 구분한 표기, manifest checksum, 적용한 `$bluetape-kotlin-patterns`
checklist ID, capability/API/source/test anchor, `resolved_dependency_receipt`,
assertion 또는 fallback 근거, Tier 1~7별 evidence와 finding count, selector별
receipt, skipped/disabled 정책, owner와 stop condition을 포함한다. PR의 exact
`head_oid`/`base_oid`/`merge_base_oid`는 PR body 또는 별도 외부 receipt에서
read-back하며 committed artifact의 필수 field로 요구하지 않는다.
reviewed-ancestor manifest에서는 marker와 `reviewed_implementation_oid`가
authoritative implementation anchor이고 legacy OID 필드는 `null`이다. 이
artifact가 없거나 ancestor OID와 receipt checksum이 맞지 않으면 child는
`READY`가 될 수 없다. P0/P1 finding이 다시 생기면 해당 Tier와 descendant를
topological order로 재검토한다.

### Track A — assertions 및 Field Service 계약

- [ ] `A1`: #783의 Field Service raw test check를 기존
  `bluetape4k-assertions` matcher family로 교체한다. concurrency `N=2`,
  operation별 deadline `2s`, `50`회 반복, 전체 test budget `60s`, cancellation
  injection `500ms`, executor termination wait `5s`를 고정하고 queue
  rejection/cancellation 결과를 검증해 공통 receipt에 기록한다. 실행 command는
  `./gradlew --no-daemon --no-build-cache --max-workers=1 --no-parallel
  :optimization-field-service-dispatch:test`다.
- [ ] `F1`: #777/#781/#782/#784에 대해 outbox reason, aggregate-version CAS,
  concurrent route, normalized-ID의 failing test를 production fix보다 먼저
  추가한다. `--no-daemon --no-build-cache --max-workers=1 --no-parallel`과
  exact selector `:optimization-field-service-dispatch:test`를 사용하고,
  in-memory fixture라도 공통 test receipt를 기록한다.
- [ ] `F2`: #804~#807의 Jackson3 mapper 생성, ID 선택, Bluetape Exposed
  repository boundary, 미사용 HTTP alias를 각각 분류한다. exact selector는
  `:optimization-field-service-dispatch:test`와
  `:optimization-field-service-dispatch:compileTestKotlin`이며 stacked head마다
  두 selector를 다시 실행한다. child timeout은 900초이고 receipt에는
  `termination_reason`과 `exit_code`를 기록한다.
- [ ] `A2`: #785~#791의 assertion/lifecycle test를 module별로 전환한다.
  framework/protocol raw check는 inventory 사유가 있는 경우만 유지한다.
  다음 selector를 각각 실행한다: `:aws-kinesis-coroutines:test`,
  `:commerce-promotion-voucher-campaign:test`,
  `:commerce-event-sourced-promotion-voucher-campaign:test`,
  `:commerce-pre-generated-voucher-pool:test`,
  `:commerce-concert-ticket-flash-sale:test`,
  `:operations-job-console-core:test`, `:operations-job-console-spring:test`,
  `:operations-job-console-ktor:test`, 그리고
  `:optimization-planning-contracts:test`; disabled test를 green aggregate
  task 뒤에 숨기지 않는다. 각 module timeout은 900초이며 다음 module을 시작하기
  전에 atomic receipt를 기록한다. browser scope는 명시적으로
  #791의 `commerce/concert-ticket-flash-sale` 파일
  `src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketBrowserContractTest.kt`
  `:commerce-concert-ticket-flash-sale:test`에서 실행한다. response/status raw
  검증은 문서화한 fallback 범위 안에서만 `behavior-under-test`로 남긴다.

### Track R — HTTP, JSON, 식별자 및 fixture

- [ ] `R1`: #793/#796/#798의 local mapper/helper 복제를 released Bluetape
  capability 소비로 전환하고, module-local feature dependency는 보존한다. 다음
  selector를 정확히 실행한다: `:commerce-usage-metering-billing-event-sourcing:test`,
  `:operations-job-console-core:test`, `:shared:test`. track timeout은 900초이며
  module task는 순차 실행하고 공통 receipt에 selector별 결과를 남긴다.
- [ ] `R2`: #794/#795의 V4/V7/namespace 의미를 분류하고 deterministic test에
  필요한 경우 generator를 주입한다. 다음 selector를 정확히 실행한다:
  `:commerce-pre-generated-voucher-pool:test`와
  `:commerce-concert-ticket-flash-sale:test`를 실행해 fixture collision/order를
  검증한다. track timeout은 900초이며 mapper/fixture compatibility failure가
  발생하면 R2를 READY로 올리지 않는다. READY 전에 receipt에서
  `r1_api_anchor`, `r1_allowed_path`, `r2_consumer_anchor`, `r2_test_anchor`의
  실행을 증명한다. anchor가 없거나 R1 mapper 경계를 호출하지 않으면 coordinator는
  R2를 `P0`로 원자적으로 reparent한다. 이때 `parent_track=P0`,
  `expected_base_ref=<frozen P0 expected_head_ref>`를 함께 갱신하고
  `parent_evidence`를 제거한다. P0가 `oid_policy=reviewed-ancestor`이면
  `parent_oid=P0.reviewed_implementation_oid`로 기록하고, exact 정책인 P0일
  때만 `parent_oid=P0.head_oid`를 사용한다. reviewed-ancestor의 legacy
  `base_oid`/`head_oid`/`merge_base_oid`는 계속 `null`이다. `state=PLANNED`,
  `receipt_status=PENDING`, `receipt_id=null`, `checksum=null`을 유지하며 사유를
  coordinator receipt에 기록한 뒤 anchor 검증을 다시 실행한다. 사유를 receipt에
  남기지 않은 stale R1 parent는 invalid train state다.

### Track T — Testcontainers 및 R2DBC

- [ ] `T1`: #779/#802/#803은 Bluetape Testcontainers wrapper를 실제 소비하고
  PostgreSQL integration을 활성화하거나 미사용 alias를 제거한다. test 전에
  `colima status`, `docker context show`, `docker info`, managed socket export를
  확인한다. `/tmp/bluetape4k-workshop-ecosystem-reuse.lock`을 30초 안에 획득하고
  owner receipt에 PID/host/start timestamp를 기록한다. 하나의 Gradle
  process with `--no-daemon --no-build-cache --max-workers=1 --no-parallel` and
  these exact selectors:

  ```text
  :commerce-usage-billing-microservices-composition-tests:integrationTest
  :spring-data-r2dbc-coroutines:test
  :gateway-api-gateway:test
  :observability-observability-basic:test
  :kotlin-coroutines:test
  :spring-data-jpa-querydsl:test
  :spring-modulith-jpa-demo:test
  ```

  Use a 1,800-second track timeout and per-selector deadline from the manifest.
  At preflight, record baseline container IDs and the exact task-owned labels
  `org.bluetape4k.ecosystem-reuse=true`,
  `org.bluetape4k.train-track=T1`, and
  `org.bluetape4k.receipt-id=RECEIPT_ID`. A finally-step records container IDs,
  logs, and status, then removes only receipt-listed task-owned non-reusable IDs
  with `docker rm -f`; never run broad
  prune or restart a healthy Colima VM. Cleanup failure records residue IDs and
  keeps the item `PENDING` before releasing the lock. Record
  pass/fail/skipped/disabled counts, container count, elapsed time, and the lock
  receipt. Skipped or disabled integration tests keep this item `PENDING` rather
  than passing.

### Track I — fencing, runtime, Money 및 launcher

- [ ] `I1`: #799~#801/#808은 계약이 맞는 경우 released Lettuce
  fencing/virtual-thread primitive를 사용하고 epoch/security 의미를 보존한다.
  Money domain policy는 명시적으로 유지하며, 안정 API가 없을 때 launcher 지원은
  `provider-gap`으로 기록한다. T1과 동일한 Docker/DB preflight와 workspace lock을
  적용하고, 하나의 process에서 `--no-daemon --no-build-cache --max-workers=1
  --no-parallel`과 다음 exact selector를 사용한다.
  `:leader-job-safety-lab:integrationTest`,
  `:commerce-usage-metering-billing-ledger:integrationTest`,
  `:operations-job-console-core:integrationTest`,
  `:spring-modulith-jpa-demo:test`, 그리고
  `:commerce-concert-ticket-flash-sale:test`를 실행한다. lock을 해제하기 전에
  공통 pass/fail/skip, container, elapsed-time, residue, termination, cleanup
  receipt를 수집한다. track timeout은 1,800초이며
  `org.bluetape4k.ecosystem-reuse=true`, `org.bluetape4k.train-track=I1`,
  `org.bluetape4k.receipt-id=RECEIPT_ID` label을 사용한다. T1과 동일한 30초
  lock, stale-PID 규칙, broad prune 금지, Colima 재시작 금지 규칙을 적용한다.

모든 child PR은 exact base/head, issue link, required checks, review artifact 중
하나라도 없으면 PR 생성·수정을 중단한다.

## Task 6: Verify the train and prepare delivery evidence

- [ ] **Step 1: Reconcile the live train**

  ```bash
  gh pr list --state open --json number,title,headRefName,baseRefName,isDraft,statusCheckRollup,url
  git worktree list --porcelain
  ```

  Select nodes by the train manifest's expected `headRefName`, not by base branch.
  For every node verify `headRefName`, planned `baseRefName`, `headRefOid`, base
  OID, and merge-base. Fail on missing, duplicate, stale, or unexpected nodes; no
  unrelated PR may be presented as part of this Epic.

- [ ] **Step 2: Verify each current head**

  For every train PR, set `pr_number` from the live `gh pr list` result, then run
  `gh pr view "$pr_number" --json headRefOid,baseRefName,body,reviews,comments,statusCheckRollup`
  and rerun the changed-module tests. A green retry does not erase a lifecycle,
  skipped-test, or stale-head gap.

- [ ] **Step 3: Invalidate descendants and prepare pre-merge closeout**

  When an ancestor head/base/merge/revert changes, mark the ancestor and every
  descendant `INVALID` in the manifest and rerun CI, targeted tests, and affected
  7-Tier lanes in topological order. Before merge, the serial closeout lane may
  verify exact-head test receipts and leave inventory entries `pending`; it must
  not claim a merge SHA or update the Epic checkbox. No active child lane may edit
  inventory or Epic state.

- [ ] **Step 4: Produce merge-ready but unmerged DoD**

  Report `Required checks: X/Y; N/A: N; Blocked: N`, exact branch/PR head SHAs,
  review/CI evidence, changed files, remaining risks, and unchecked CG-16~CG-18.
  The final state is `PENDING` until the user gives fresh merge approval for the
  exact current head.

- [ ] **Step 5: Conditional post-merge closeout**

  After CG-16 fresh approval for the exact current head, the serial closeout lane
  executes GitHub's `rebase` merge strategy only (never squash or auto-merge),
  verifies the resulting merge state and commit on `develop`, consumes the
  exact-head receipt, changes inventory `pending` to `verified`, and updates the
  Epic checkbox. A missing merge SHA or non-ancestor develop head leaves the node
  `MERGE_READY`/`PENDING` and does not advance descendants.

## Rollback and rerun points

- Before GitHub issue metadata mutation: restore the complete body captured by
  `gh issue view 792 --json body`; never patch an empty body.
- Before committing P0: revert only uncommitted P0 docs/scripts in the isolated
  worktree; the main checkout's dirty workflow remains untouched.
- Before each child stack update: preserve the previous remote head and do not
  force-push a stale base. Recreate the child branch from its last verified parent
  if the parent changes materially.
- For GitHub mutation, use the captured preimage and stable markers; mutation
  results are `applied`, `not-applied`, or `unknown` until a complete read-back
  proves otherwise. Blind retries are forbidden.
- On cancellation or timeout, mark the lane `PENDING`, preserve the receipt and
  task-owned resource IDs, and stop all descendants. Resolve every `unknown`
  mutation during the next startup recovery preflight before writing again.
- On a failing Testcontainers or concurrency test: diagnose the first raw failure,
  fix the owning track, and rerun the same sequential command before broader CI.

## 2026-08-27 serial post-merge closeout

- F1 PR #815는 `2c388e1dba3de5a9636b1528c68d1da758601e76`로, descendant P2-02
  PR #821은 `f95ea45c1c053f3901d91d29bca58f4e18fb3bdf`로 rebase merge했다.
- A2 PR #829는 `323290adec9e8904f566ed736217369d78313896`로 rebase merge했고,
  coordinator manifest repair PR #830은 `8550c08b40e671fd48dea8d1ad0d59f8868210a0`로
  rebase merge했다.
- serial closeout manifest는 A2/F1을 법적 상태 전이상 `READY/PASS`로 기록하고,
  receipts `20260827T102153Z-a2-merge-closeout`와
  `20260827T102153Z-f1-p2-merge-closeout`를 결속한다. inventory는 직접 변경된
  #777, #782, #787, #791만 `verified`로 전환했으며 #781, #784, #785와 나머지
  train track은 `pending`으로 유지한다.
- Epic #792는 F2/R1/R2/T1/I1 및 명시적 후속 issue가 남아 있으므로 열린 상태를
  유지한다. 이후 child는 최신 `develop`을 parent로 삼고 exact-head receipt와
  사용자의 fresh approval 후에만 rebase merge한다.

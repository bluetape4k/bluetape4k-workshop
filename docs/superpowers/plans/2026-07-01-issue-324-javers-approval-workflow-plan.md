# Issue #324 - JaVers Approval Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use the bluetape4k workflow,
> code-pattern, blog, and diagram skills. Steps use checkbox (`- [ ]`) syntax
> for tracking.

**Goal:** Build a JaVers approval workflow workshop module that teaches
pre-commit diff review, approve/reject decisions, and approved audit lookup.

**Architecture:** The module keeps Exposed/H2 as the current-state and decision
store, while JaVers in-memory snapshots represent only approved aggregate
history. The service compares current vs proposed aggregates before persistence,
then commits only approved proposals.

**Tech Stack:** Kotlin, Exposed JDBC, H2, `bluetape4k-javers-core`,
`bluetape4k-assertions`, JaVers `Diff` / `ValueChange`, CairoSVG diagram
rendering, GitHub Actions Examples workflow.

---

### Task 1: Module Skeleton And Red Tests

**Files:**
- Create: `exposed/javers-approval-workflow/build.gradle.kts`
- Create: `exposed/javers-approval-workflow/src/test/resources/junit-platform.properties`
- Create: `exposed/javers-approval-workflow/src/test/resources/logback-test.xml`
- Create: `exposed/javers-approval-workflow/src/test/kotlin/io/bluetape4k/workshop/exposed/javers/approval/ProductPolicyApprovalServiceTest.kt`

- [ ] Add the build script using the root BOM-resolved aliases:
  `libs.bluetape4k.javers.core`, `libs.exposed.core`, `libs.exposed.jdbc`,
  `libs.h2.v2`, `libs.bluetape4k.assertions`, and `libs.exposed.jdbc.tests`.
- [ ] Add tests first for:
  - proposed scalar and nested value-object diffs;
  - approval updating the current row and JaVers history;
  - rejection leaving the current row and JaVers history unchanged;
  - audit lookup returning approved snapshots only.
- [ ] Run:
  `./gradlew :exposed-javers-approval-workflow:test --console=plain --max-workers=1`
- [ ] Expected red result: unresolved production symbols for the approval
  workflow classes.

### Task 2: Domain, Tables, And Service

**Files:**
- Create: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/model/ProductPolicy.kt`
- Create: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/model/ProductPolicyTable.kt`
- Create: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/model/PolicyProposalTable.kt`
- Create: `exposed/javers-approval-workflow/src/main/kotlin/io/bluetape4k/workshop/exposed/javers/approval/service/ProductPolicyApprovalService.kt`

- [ ] Implement serializable immutable domain values:
  `ProductPolicy`, `PricingPolicy`, `PolicyStatus`, `ProposalStatus`,
  `PolicyProposal`, and `ChangedField`.
- [ ] Implement Exposed tables for the approved current policy and proposal
  decisions. Store proposed/current snapshots as JSON text to keep the example
  explicit and dependency-light.
- [ ] Implement `ProductPolicyApprovalService` with `publishInitial`,
  `submitProposal`, `approveProposal`, `rejectProposal`, `findProposal`,
  `findCurrentPolicy`, and `getHistory`.
- [ ] Use bluetape4k validation helpers for caller input and Exposed v1
  top-level imports for `eq`, `deleteWhere`, and `upsert`.
- [ ] Run:
  `./gradlew :exposed-javers-approval-workflow:test --console=plain --max-workers=1 --rerun-tasks`
- [ ] Expected result: tests pass.

### Task 3: Learner Documentation

**Files:**
- Create: `exposed/javers-approval-workflow/README.md`
- Create: `exposed/javers-approval-workflow/README.ko.md`
- Modify: `README.md`
- Modify: `README.ko.md`

- [ ] Write English and Korean module READMEs with a language switch, overview,
  architecture, workflow, code snippets, and “append-only audit vs approval
  workflow” comparison.
- [ ] Add the module row to both root README files under Data Access.
- [ ] Keep diagram labels in English and Korean prose natural.
- [ ] Run README validators if present:
  `ls scripts/*readme*` and then the applicable `node scripts/...` commands.

### Task 4: Diagrams

**Files:**
- Create: `docs/images/readme-diagrams/exposed-javers-approval-workflow-architecture-01.svg`
- Create: `docs/images/readme-diagrams/exposed-javers-approval-workflow-architecture-01.png`
- Create: `docs/images/readme-diagrams/exposed-javers-approval-workflow-sequence-01.svg`
- Create: `docs/images/readme-diagrams/exposed-javers-approval-workflow-sequence-01.png`

- [ ] Open current best-practices architecture and sequence reference PNGs
  before drawing, and record the paths in the evidence ledger.
- [ ] Draw the architecture diagram as a static ownership/dependency view with
  clear layers, consistent card alignment, official/catalog database icon use
  only where applicable, and a legend if connector styles differ.
- [ ] Draw the sequence diagram from the established best-practices sequence
  family: numbered labels above lines, transparent `alt` body, branch-specific
  muted colors, matching arrowhead colors, and enough row height.
- [ ] Render each SVG with:
  `~/.local/bin/cairosvg <svg> -o <png> -s 2`
- [ ] Run `node scripts/validate-readme-diagram-qa.mjs` and the relevant
  `bluetape4k-diagram/references/*.py` audits for connector-heavy assets.
- [ ] Open every touched PNG full-size and reject any connector/card/text,
  rounded-corner, arrowhead, palette, or sequence-style defect.

### Task 5: Workflow Registration And Final Verification

**Files:**
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] Add `exposed/javers-approval-workflow/**` to push and PR path filters.
- [ ] Add `:exposed-javers-approval-workflow:test` to the H2/default smoke lane
  and smoke result artifacts.
- [ ] Add the module to `scripts/smoke-validate.sh` no-container/data-access
  checks and increment stale-check expected project count from 93 to 94.
- [ ] Run:
  - `./gradlew projects --console=plain`
  - `./scripts/smoke-validate.sh stale-check`
  - `actionlint .github/workflows/Examples.yml`
  - `git diff --check`
- [ ] Commit with Lore protocol, create a PR assigned to `debop`, mirror issue
  #324 milestone/labels, and verify the live PR body ends with `## DoD Status`.

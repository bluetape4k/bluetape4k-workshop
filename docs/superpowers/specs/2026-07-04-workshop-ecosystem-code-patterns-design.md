# bluetape4k-workshop Ecosystem Code Patterns Design

Date: 2026-07-04
Repository: `bluetape4k-workshop`
Branch: `feat/workshop-ecosystem-code-patterns`

## Problem

`bluetape4k-workshop` is a consumer-example repository. Its examples should
teach users to solve common backend problems with bluetape4k ecosystem
libraries first, not with raw JDK, raw third-party APIs, or ad hoc local
helpers when a bluetape4k helper already exists.

Recent cleanup PRs already resolved several broad classes:

- PR #379 removed raw UUID string generation and added missing serialization
  metadata in selected examples.
- PR #389 reduced residual ecosystem-pattern drift and created follow-up
  issues for validation, blocking boundaries, and test null assertions.
- PR #393 refactored many raw `require(...)` checks to bluetape4k validation
  helpers.
- Issues #390, #391, #392, and parent epic #380 are closed, so this work must
  avoid duplicating those completed changes.

The remaining work is a module-by-module review and targeted refactor pass that
prioritizes example quality: each touched module should demonstrate bluetape4k
validation, assertions, coroutine/test helpers, logging, Testcontainers
launchers, and other ecosystem APIs where they fit the example boundary.

## Current Evidence

- `repo-status` before spec authoring: branch
  `feat/workshop-ecosystem-code-patterns`, upstream `origin/develop`, clean
  working tree before this spec file was created.
- `./gradlew projects --console=plain`: `BUILD SUCCESSFUL in 11s`; registered
  Gradle projects are visible.
- Registered project inventory: 100 Gradle projects including `:shared`.
- Baseline build: `./gradlew build --max-workers=1 --console=plain` passed with
  `BUILD SUCCESSFUL in 2m 44s`.
- GitHub state: no open issues and no open PRs at start.
- Milestone state: `backlog` is open and available for new PR metadata.
- GNO evidence found prior workshop ecosystem-pattern work: issue #223 and
  coverage/validation matrix documents.
- A broad Kotlin pattern scan found 62 of 100 registered projects with at least
  one candidate pattern such as `Thread.sleep`, raw `require`, `checkNotNull`,
  boolean/size assertion shape, test `!!`, `runBlocking`, `runCatching`, or
  `synchronized`.

High-density candidate modules from the initial scan:

| Project | Main candidate classes |
|---|---|
| `:okio-examples` | raw validation, sample assertion style, blocking examples |
| `:redis-redisson-examples` | sleeps and timing-oriented tests/examples |
| `:image-processing-advanced-workflow` | raw validation and weak assertion shape |
| `:virtualthreads-rules` | sleep/synchronization examples that need teaching-intent review |
| `:image-processing-ocr-api` | raw validation with sensitive/public error contracts |
| `:leader-leader-election` | sleep/blocking lifecycle examples |
| `:kotlin-text-processing` | raw validation |
| `:leader-leader-zookeeper` | blocking bridge and lifecycle examples |
| `:leader-tenant-scheduler` | raw validation |
| `:messaging-kafka-outbox-fallback` | assertion shape and validation |
| `:spring-boot-cache-caffeine`, `:spring-boot-cache-redis` | request-path blocking latency simulation |
| `:gatling-virtualthread-simulation` | load-simulation blocking behavior |
| `:spring-data-*`, `:spring-boot-*`, `:redis-*` | mixed assertion, blocking, and lifecycle candidates |

## Goals

1. Review all registered Gradle projects with the 7-Tier frame.
2. Patch only modules with concrete, safe ecosystem-reuse improvements.
3. Create separate PRs per changed Gradle project.
4. Preserve no-op review evidence for modules that need no patch.
5. Keep PRs small enough for focused review, validation, and CI.
6. End every PR body with `## DoD Status` and verify the live body with
   `gh pr view --json body`.

## Non-Goals

- Do not reopen already-closed broad cleanup issues #390, #391, #392, or #380.
- Do not mechanically remove every `Thread.sleep`, `runBlocking`, `check`, or
  `runCatching` when it is intentionally demonstrating blocking behavior,
  virtual-thread behavior, internal invariants, or failure simulation.
- Do not create new dependencies unless explicitly required and approved.
- Do not change workflow YAML unless module-registration or CI evidence shows a
  real gap.
- Do not merge PRs automatically.

## Approach Options

### Option A: One Repository-Wide PR

This is simple to execute but too broad for review. It risks mixing unrelated
module behavior, making CI failures hard to isolate, and hiding module-specific
teaching intent.

Rejected.

### Option B: One PR Per Registered Gradle Project

This aligns with the user's requested module-by-module shape. Each PR has a
clear module owner, targeted validation, a per-module 7-Tier review artifact,
and narrow CI/debug surface. Empty modules are recorded as no-op review entries
instead of creating empty PRs.

Selected.

### Option C: One PR Per Domain Directory

This reduces PR count but groups unrelated submodules together, for example
`spring-data` or `spring-boot` examples with different runtime dependencies and
test profiles. It is useful only as a fallback when several modules require the
same shared fix.

Fallback only.

## Selected Design

Run a staged module-by-module workflow:

1. Build a registered-project inventory from `settings.gradle.kts`.
2. Run a repeatable ecosystem-pattern scan per project.
3. Process candidate projects by density and risk, starting with modules that
   have the strongest actionable evidence.
4. For each candidate project:
   - inspect current source/tests/docs;
   - search for existing bluetape4k ecosystem helpers before designing a fix;
   - write or adjust tests first when behavior changes;
   - apply minimal code-pattern refactors;
   - run compile/tests for that project;
   - run module-scoped 7-Tier review and save `docs/review/...`;
   - add a short lesson when the work reveals a reusable future guard;
   - commit and open a PR for that project only.
5. For projects with no safe patch, add them to a tracked no-op review matrix
   at `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`.
   No-op status is allowed only after module-scoped 7-Tier review confirms
   P0/P1=0, including stability review for race, deadlock, leak, cancellation,
   lifecycle, contention, and Testcontainers risk.

## Module Branch and PR Runbook

The coordination branch (`feat/workshop-ecosystem-code-patterns`) owns this
spec, the implementation plan, and the review matrix. Each changed Gradle
project gets its own module branch and PR:

| Branch kind | Naming | Contents |
|---|---|---|
| Coordination | `feat/workshop-ecosystem-code-patterns` | Spec, plan, review matrix, wave status |
| Module PR | `refactor/<project-slug>-ecosystem-patterns` | One Gradle project's source/test/docs changes plus its review/lesson artifacts |
| Shared helper exception | `refactor/shared-ecosystem-patterns` | Only shared helper changes required by more than one module |

Rules:

- Create module branches from current `origin/develop`, not from a dirty module
  branch.
- Keep at most three active module PRs at a time unless the user explicitly
  asks for a larger batch.
- Before each wave and before every PR creation, refresh `origin/develop`,
  check open PRs/issues, verify local status is clean, and confirm no shared
  helper change has invalidated earlier module assumptions.
- If a module branch fails validation or becomes superseded, close or mark the
  PR as superseded, preserve local evidence, and create a replacement branch
  from current `origin/develop`.
- Do not delete local or remote branches unless the user requests cleanup or
  safety is proven by default-branch ancestry or patch-equivalence.

The no-op matrix schema is:

| Column | Meaning |
|---|---|
| Project | Gradle project path |
| Directory | Source directory |
| Candidate patterns | Scan hits or risk classes reviewed |
| Disposition | `patched`, `no-op`, `follow-up`, or `blocked` |
| Ecosystem reuse evidence | Helper/API adopted, already present, or rejected with reason |
| Stability/security verdict | P0/P1 state and rationale |
| Validation evidence | Commands, source lines, or PR number |
| Reviewer/date | Review owner and date |

## Ecosystem Reuse Acceptance Criteria

Every changed project must record evidence for these checks:

- Caller-input validation uses bluetape4k `require*` helpers when semantics
  match.
- Tests use `bluetape4k-assertions` assertion shapes in touched assertions.
- Coroutine, Flow, and async examples preserve cancellation semantics and avoid
  `runCatching` around suspend paths unless the code is intentionally teaching a
  boundary.
- Concurrency or stress tests use `bluetape4k-junit5` helpers
  (`MultithreadingTester`, `SuspendedJobTester`, or
  `StructuredTaskScopeTester`) when those helpers fit the risk.
- Testcontainers-backed examples use bluetape4k launcher singletons when the
  ecosystem provides a launcher for the infrastructure.
- Logging uses bluetape4k logging patterns instead of runtime `println` in
  production paths.
- Opaque string identifiers use bluetape4k ID/string helpers such as
  `Base58.randomString(...)` when unique-string semantics are needed.
- Existing bluetape4k coroutine, lifecycle, date/time, collection, and support
  helpers are preferred over raw JDK or generic third-party utilities when they
  fit the example's purpose.
- README-impacting changes update `README.md` and every existing localized
  README such as `README.ko.md` together.
- Public KDoc/API documentation is written in English.
- PR bodies include a short "What this teaches" section that names the
  bluetape4k ecosystem pattern demonstrated by the module change.
- Public README/KDoc/example names are grep-checked against current source
  before docs are claimed current.

## Blocking, Sleep, and Teaching-Intent Classification

Every `Thread.sleep`, `runBlocking`, `runCatching`, lock, `close`/cleanup, and
suspend-loop match is classified before patching:

| Class | Default action |
|---|---|
| Intentional teaching simulation | Keep only when the module explicitly teaches blocking, latency, virtual threads, or failure simulation; record rationale in review evidence |
| Request-path or hot-path behavior | Prefer bluetape4k coroutine/lifecycle helpers, non-blocking APIs, or documented simulator boundaries |
| Fragile async/test wait | Replace with Awaitility, `untilAsserted`, `untilSuspending`, or bluetape4k junit5 testers where appropriate |
| Concurrency or cancellation stress | Use `MultithreadingTester`, `SuspendedJobTester`, or `StructuredTaskScopeTester` when the helper fits |
| Lifecycle cleanup | Ensure independent cleanup, cancellation rethrow, and no suspend `runCatching` unless explicitly safe |
| README/KDoc snippet | Keep simple `println` examples when they are documentation-only snippets and not production code guidance |

Edits around suspend cancellation, blocking bridges, sleeps, locks, lifecycle
close/cleanup, and Testcontainers launchers are treated as stability-affecting
unless the review artifact proves otherwise.

## Security Acceptance Criteria

Security-sensitive modules must preserve or add evidence for these checks:

- Public responses, warnings, logs, stored summaries, and docs do not echo raw
  exception messages, stack traces, native OCR/tessdata paths, uploaded content,
  JWT/Bearer tokens, Authorization values, idempotency keys, access keys,
  secret keys, session tokens, passwords, credentials, or raw request bodies.
- Existing non-echoing public error contracts, such as OCR native failure
  handling, remain covered by tests when touched.
- Log standardization must not preserve or introduce sensitive value leakage.
- SQL/NoSQL calls do not interpolate caller input into query strings when a
  structured API or bind parameter exists.
- Deserialization examples avoid broad polymorphic default typing over `Any` in
  learner-facing or production-like paths. Test-only examples must document the
  boundary and prefer package/type-constrained validators.
- Public leak of tokens, credentials, secret paths, or raw native errors is P1.
  Unsafe deserialization defaults without an exposed untrusted path are at
  least P2 unless proven test-only and documented.

## Review Frame

Each changed project gets a module-scoped 7-Tier review:

1. Security: validation, injection, secrets, unsafe defaults.
2. Ops/SRE reliability: lifecycle, cleanup, logging, diagnostics.
3. Structural impact: module boundaries, dependencies, registration.
4. Kotlin code quality: idioms, bluetape4k-code-patterns, public docs.
5. Tests/types/silent failure: assertions, failure coverage, false positives.
6. Performance/stability: blocking, sleeps, contention, cancellation.
7. Documentation/release/evidence: README impact, PR body, CI, DoD.

P0/P1 findings block PR creation. P2/P3 findings may be fixed if local and
cheap, or recorded as follow-up rationale.

## PR Metadata

For every created PR:

- Title/body are English.
- Assignee is `debop`.
- Milestone is `backlog` unless a more precise live milestone appears.
- Labels mirror the touched area, for example `refactoring`,
  `area:governance`, `area:async-reactive`, `area:data-access`,
  `area:spring-boot`, or `area:architecture-extension`.
- The final Markdown `##` heading is `## DoD Status`.
- Live PR body is verified with `gh pr view <number> --json body`.
- Live metadata is verified with
  `gh pr view <number> --json headRefName,baseRefName,assignees,labels,milestone,body,statusCheckRollup`.
- `gh pr checks <number>` or `statusCheckRollup` is reviewed. If GitHub path
  filters skip module tests, local targeted tests are recorded as the required
  test evidence instead of assuming CI covered the module.

## Validation Strategy

For each changed project:

- Run targeted compile first:
  `./gradlew :<project>:compileKotlin :<project>:compileTestKotlin --max-workers=1 --warning-mode all --console=plain`.
- Run targeted tests:
  `./gradlew :<project>:test --max-workers=1 --warning-mode all --console=plain`.
- Use `cleanTest --no-build-cache` for Testcontainers-backed or cache-sensitive
  modules.
- Run `git diff --check`.
- Run `actionlint` only if workflow YAML changes.
- Run full repository build only after a fixed wave of up to three module PRs,
  after shared code changes, or before final closeout.
- Do not run Testcontainers-backed Gradle processes in parallel across agents,
  worktrees, waves, or separate Gradle JVMs. Use one combined Gradle invocation
  or explicit sequential commands.
- After an interrupted Testcontainers run, inspect labeled
  `org.testcontainers=true` residue before rerun; remove resources only when
  cleanup is explicitly requested or clearly safe.
- For observability, virtual-thread, AWS logging, or tracing examples, verify
  lazy logging, no sensitive diagnostic output, bounded metric label
  cardinality, trace/span propagation where relevant, and the appropriate
  `scripts/smoke-validate.sh` group when the script covers the touched module.
- For benchmark, Gatling, or performance-demo modules, preserve intentional
  load simulation, do not introduce ad hoc benchmark harnesses, and record
  README/test/smoke evidence for any behavior change.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Mechanical cleanup changes teaching examples incorrectly | Review each match by intent and preserve documented blocking/virtual-thread demonstrations |
| 100 PRs would create noise | Create PRs only for changed projects; keep at most three active module PRs; no-op reviews go into a matrix |
| Testcontainers flakiness | Run container-backed Gradle tasks serially and use `cleanTest --no-build-cache` when stale state can hide failures |
| Shared helper edits affect many modules | Prefer module-local PRs; split shared helper changes into their own PR only when required |
| Duplicate already-closed cleanup | Check PR #379/#389/#393 and issues #390/#391/#392/#380 before filing or patching broad classes |
| Failed or superseded module PR | Preserve evidence, close/supersede the PR, recreate from current `origin/develop`, and document the rollback/supersede reason |

## Stop Condition

Stop when all 100 registered projects are classified as either:

- patched and represented by a module PR with passing local validation,
  P0/P1=0 review evidence, and verified live PR body; or
- no-op reviewed in
  `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md` with a
  documented reason, P0/P1=0 stability/security verdict, and no code patch.

Do not merge PRs automatically.

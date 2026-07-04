# Workshop Ecosystem Code Patterns Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Review every registered `bluetape4k-workshop` Gradle project and create separate PRs for modules that can better demonstrate bluetape4k ecosystem library usage.

**Architecture:** Use one coordination branch for the spec, plan, and review matrix, then create one branch/PR per changed Gradle project from current `origin/develop`. Each module PR owns only that module's source/test/docs plus its review/lesson artifacts; no-op modules are recorded in a durable matrix with P0/P1=0 evidence.

**Tech Stack:** Kotlin 2.4, Java 21, Spring Boot 4.0.6, Gradle, bluetape4k-dependencies BOM, bluetape4k validation helpers, bluetape4k-assertions, bluetape4k-junit5, bluetape4k logging, bluetape4k Testcontainers launcher patterns, GitHub CLI.

---

## Files and Artifacts

Coordination branch files:

- Existing spec: `docs/superpowers/specs/2026-07-04-workshop-ecosystem-code-patterns-design.md`
- This plan: `docs/superpowers/plans/2026-07-04-workshop-ecosystem-code-patterns-plan.md`
- Create: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

Per-module PR files:

- Modify only the module directory, for example `io/okio-examples/**` for `:okio-examples`.
- Add one review artifact per changed module, for example `docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md`.
- Add one lesson only when useful, for example `docs/lessons/2026-07-04-spring-boot-cache-caffeine-ecosystem-patterns.md`.
- Update `README.md` plus existing localized README files when public example behavior or module-facing docs change.

## Wave Order

Keep at most three active module PRs at a time.

| Wave | Projects | Reason |
|---|---|---|
| 1 | `:spring-boot-cache-caffeine`, `:spring-boot-cache-redis`, `:gatling-virtualthread-simulation` | Hot-path/request-path or load-simulation blocking examples |
| 2 | `:okio-examples`, `:image-processing-advanced-workflow`, `:image-processing-ocr-api` | High-density validation/docs/security contracts |
| 3 | `:leader-leader-election`, `:leader-leader-zookeeper`, `:leader-tenant-scheduler` | Blocking bridges, scheduler lifecycle, validation |
| 4 | `:redis-redisson-examples`, `:redis-distributed-lock`, `:redis-cluster-demo` | Timing, lock/lifecycle, Testcontainers/Redis examples |
| 5A | `:messaging-kafka-outbox-fallback`, `:messaging-kafka`, `:messaging-kafka-reply` | Messaging validation, redaction, assertion shape |
| 5B | `:messaging-transactional-outbox` plus the next two matrix-ranked messaging/data candidates | Keep active PR count at three or fewer |
| 6 | `:spring-data-*` candidates in batches of three or fewer | Data-access assertions, deserialization, Testcontainers and reactive/coroutine contracts |
| 7 | `:spring-boot-*` remaining candidates in batches of three or fewer | Web/cache/resilience/idempotency/security examples |
| 8 | All remaining registered projects in matrix order, three or fewer active PRs | Confirm patched/no-op status until 100 projects are classified |

## Global Execution Guards

- Treat the matrix as the work queue. A batch may have at most three active
  module PRs, and Testcontainers-backed Gradle commands always run in a single
  serialized owner lane even when read-only scans or non-container tests run in
  parallel.
- Before launching a Testcontainers-backed Gradle task, record the owner
  branch, worktree path, command, start time, and stop time in the matrix. Do
  not start another Testcontainers-backed Gradle task until the current one has
  completed or been marked blocked with cleanup evidence.
- If a Gradle/Testcontainers task hangs or is interrupted, stop the affected
  process, inspect local Docker/Testcontainers residue, rerun once only after a
  clean state is confirmed, and otherwise mark the module `blocked` with the
  log path and reason.
- Before pushing any module branch, refresh `origin/develop` and verify the
  branch still descends from the observed base. If the base moved, rebase or
  recreate the module branch from current `origin/develop`, then rerun compile,
  tests, review, and PR body checks.
- Failed or superseded module PRs keep their evidence: comment the failure or
  supersession reason on the PR, update the matrix disposition, create any
  replacement branch from current `origin/develop`, and do not delete local or
  remote branches unless cleanup is separately requested or safety-proven.

## Task 1: Create the Coordination Matrix

**Files:**
- Create: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **Step 1: Generate registered project inventory**

Run:

```bash
node - <<'NODE'
const fs = require("fs")
const path = require("path")
const root = process.cwd()
const specs = [
  ["aws", false, true],
  ["examples", false, false],
  ["exposed", false, true],
  ["gateway", false, false],
  ["gatling", false, true],
  ["graalvm", false, false],
  ["graph", false, true],
  ["image-processing", false, true],
  ["io", false, false],
  ["json", false, false],
  ["kotlin", false, true],
  ["ktor", false, true],
  ["leader", false, true],
  ["messaging", false, true],
  ["observability", false, false],
  ["ratelimit", false, false],
  ["redis", false, true],
  ["spring-boot", false, true],
  ["spring-data", false, true],
  ["spring-modulith", false, true],
  ["spring-security/mvc", false, true],
  ["spring-security/webflux", false, true],
  ["vertx", false, true],
  ["virtualthreads", false, true],
]
const modules = [{ project: ":shared", dir: "shared" }]
for (const [base, withProjectName, withBaseDir] of specs) {
  const basePath = path.join(root, base)
  if (!fs.existsSync(basePath)) continue
  for (const entry of fs.readdirSync(basePath, { withFileTypes: true }).filter((it) => it.isDirectory()).map((it) => it.name).sort()) {
    const dir = path.join(base, entry)
    if (!fs.existsSync(path.join(root, dir, "build.gradle.kts"))) continue
    const baseDash = base.replace(/\//g, "-")
    const projectName = !withProjectName && !withBaseDir
      ? entry
      : withProjectName && !withBaseDir
        ? "bluetape4k-" + entry
        : withProjectName
          ? "bluetape4k-" + baseDash + "-" + entry
          : baseDash + "-" + entry
    modules.push({ project: ":" + projectName, dir })
  }
}
console.log("| Project | Directory | Candidate patterns | Disposition | Ecosystem reuse evidence | Stability/security verdict | Validation evidence | Reviewer/date |")
console.log("|---|---|---|---|---|---|---|---|")
for (const m of modules) {
  console.log(`| \`${m.project}\` | \`${m.dir}\` | pending scan | blocked | pending | pending | pending | Codex / 2026-07-04 |`)
}
NODE
```

Expected: a Markdown table with 100 project rows.

- [ ] **Step 2: Create the matrix file**

Copy the generated table into:

```markdown
# Workshop Ecosystem Code Patterns Matrix

Date: 2026-07-04
Coordination branch: `feat/workshop-ecosystem-code-patterns`

This matrix tracks every registered Gradle project. A row reaches terminal
state only when disposition is `patched`, `no-op`, or `follow-up` with P0/P1=0
review evidence.

Paste the exact table emitted by Step 1 here, then keep every row updated until
the project reaches a terminal disposition.
```

- [ ] **Step 3: Verify the matrix row count**

Run:

```bash
grep -Ec '^\| `:' docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md
```

Expected: `100`.

- [ ] **Step 4: Verify the matrix against Gradle projects**

Run:

```bash
./gradlew -q projects --console=plain > /tmp/workshop-gradle-projects.txt
node - <<'NODE'
const fs = require("fs")
const matrix = fs.readFileSync("docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md", "utf8")
  .split(/\r?\n/)
  .map((line) => (line.match(/^\| `([^`]+)` \|/) || [])[1])
  .filter(Boolean)
  .sort()
const gradle = fs.readFileSync("/tmp/workshop-gradle-projects.txt", "utf8")
  .split(/\r?\n/)
  .map((line) => (line.match(/Project '(:[^']+)'/) || [])[1])
  .filter(Boolean)
  .sort()
const missing = gradle.filter((it) => !matrix.includes(it))
const extra = matrix.filter((it) => !gradle.includes(it))
if (missing.length || extra.length) {
  console.error(JSON.stringify({ missing, extra }, null, 2))
  process.exit(1)
}
console.log(`matrix matches Gradle projects: ${matrix.length}`)
NODE
```

Expected: `matrix matches Gradle projects: 100`.

- [ ] **Step 5: Commit the matrix with the plan**

Commit after Step 3-R plan review passes, not before.

## Task 2: Run Repeatable Candidate Scans

**Files:**
- Modify: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **Step 1: Run Kotlin ecosystem-pattern scan**

Run:

```bash
rg -n "Thread\\.sleep|\\brunBlocking\\s*\\(|runCatching\\s*\\{|\\brequire\\s*\\(|checkNotNull\\s*\\(|\\bcheck\\s*\\(|shouldBeEqualTo\\s+(true|false)|shouldBeEqualTo\\((true|false)\\)|\\.size\\s+shouldBeEqualTo|!!|@Synchronized|synchronized\\s*\\(" --glob '*.kt'
```

Expected: matches are reviewed by module; a match is not automatically a defect.

- [ ] **Step 2: Run security-oriented scan**

Run:

```bash
rg -n "Bearer|Authorization|accessKey|secretKey|sessionToken|password|credential|Idempotency-Key|token|stackTrace|printStackTrace|stackTraceToString|exception\\.message|\\$\\{[^}]*\\.message\\}|\\$ex|@ExceptionHandler|ProblemDetail|ErrorResponse|logger\\.(trace|debug|info|warn|error)\\([^\\n]*e\\)|message\\s*[=:]" --glob '*.kt' --glob '*.md'
```

Expected: matches are reviewed for leak risk, redaction, teaching intent, or test-only scope.

- [ ] **Step 3: Run config/default-risk security scan**

Run:

```bash
rg -n "password|secret|apiKey|accessKey|clientSecret|privateKey|management\\.endpoints\\.web\\.exposure\\.include\\s*=\\s*\\*|management\\.endpoints\\.web\\.exposure\\.include:\\s*['\"]?\\*|csrf\\.disable|permitAll|allowedOrigins\\(\"\\*\"\\)|allowed-origins:\\s*\\*|debug:\\s*true|trace:\\s*true|Authorization|Bearer" --glob '*.yml' --glob '*.yaml' --glob '*.properties' --glob '.env*' --glob '*.md'
```

Expected: each hit is classified as safe default, local/test-only example, or
issue candidate. Real-looking secrets, broad actuator exposure, insecure CORS,
CSRF disablement, and permit-all security examples need explicit teaching-only
rationale and tests where touched.

- [ ] **Step 4: Run injection and deserialization scan**

Run:

```bash
rg -n "activateDefaultTyping|DefaultTyping|@JsonTypeInfo\\(|readValue<Any>|GenericJackson2JsonRedisSerializer|@Query\\(|createQuery\\(|nativeQuery|SELECT .*\\$|WHERE .*\\$|\\$\\{.*\\}" --glob '*.kt' --glob '*.md'
```

Expected: structured/bind API evidence, constrained type validator evidence,
or documented test-only boundary for every relevant hit.

- [ ] **Step 5: Run Testcontainers/direct-container scan**

Run:

```bash
rg -n "GenericContainer\\(|DockerImageName|Testcontainers|Launcher\\." --glob '*.kt'
```

Expected: direct container usage is either replaced with bluetape4k launchers where available, or recorded as a module-specific exception.

- [ ] **Step 6: Update matrix candidates**

For each registered project, replace `pending scan` with a concise candidate class such as:

```text
raw validation; blocking simulation; weak assertions; sensitive logging; no candidate
```

## Task 3: Process Wave 1 Module PRs

Create one branch and one PR for each Wave 1 project:

| Project | Directory | Branch | Review artifact | Test command prefix |
|---|---|---|---|---|
| `:spring-boot-cache-caffeine` | `spring-boot/cache-caffeine` | `refactor/spring-boot-cache-caffeine-ecosystem-patterns` | `docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md` | `./gradlew :spring-boot-cache-caffeine` |
| `:spring-boot-cache-redis` | `spring-boot/cache-redis` | `refactor/spring-boot-cache-redis-ecosystem-patterns` | `docs/review/2026-07-04-spring-boot-cache-redis-ecosystem-review.md` | `./gradlew :spring-boot-cache-redis` |
| `:gatling-virtualthread-simulation` | `gatling/virtualthread-simulation` | `refactor/gatling-virtualthread-simulation-ecosystem-patterns` | `docs/review/2026-07-04-gatling-virtualthread-simulation-ecosystem-review.md` | `./gradlew :gatling-virtualthread-simulation` |

- [ ] **Step 1: Refresh branch state**

Run from the main repository checkout:

```bash
git fetch --prune origin develop
repo-status
gh pr list --state open --json number,title,headRefName,baseRefName,labels,milestone,assignees
worktree-list
```

Expected: current state, active PR count, and branch-to-worktree mapping are
understood before creating the module branch.

- [ ] **Step 2: Create the module branch worktree**

For each Wave 1 row, run the corresponding command:

```bash
worktree-new refactor/spring-boot-cache-caffeine-ecosystem-patterns --base origin/develop
worktree-new refactor/spring-boot-cache-redis-ecosystem-patterns --base origin/develop
worktree-new refactor/gatling-virtualthread-simulation-ecosystem-patterns --base origin/develop
```

Expected: one worktree exists for each branch. Do not run Testcontainers-backed
Gradle tasks concurrently across these worktrees.

- [ ] **Step 3: Inspect the module**

Run inside the module worktree:

```bash
rg -n "Thread\\.sleep|\\brunBlocking\\s*\\(|runCatching\\s*\\{|\\brequire\\s*\\(|checkNotNull\\s*\\(|\\bcheck\\s*\\(|shouldBeEqualTo\\s+(true|false)|shouldBeEqualTo\\((true|false)\\)|\\.size\\s+shouldBeEqualTo|!!|@Synchronized|synchronized\\s*\\(" spring-boot/cache-caffeine --glob '*.kt'
rg -n "Bearer|Authorization|accessKey|secretKey|sessionToken|password|credential|Idempotency-Key|token|stackTrace|printStackTrace|exception\\.message|\\.message\\}" spring-boot/cache-caffeine --glob '*.kt' --glob '*.md'
rg -n "password|secret|apiKey|accessKey|clientSecret|privateKey|management\\.endpoints\\.web\\.exposure\\.include|csrf\\.disable|permitAll|allowedOrigins\\(\"\\*\"\\)|debug:\\s*true|trace:\\s*true" spring-boot/cache-caffeine --glob '*.yml' --glob '*.yaml' --glob '*.properties' --glob '.env*' --glob '*.md'
rg -n "activateDefaultTyping|DefaultTyping|@JsonTypeInfo\\(|readValue<Any>|GenericJackson2JsonRedisSerializer|@Query\\(|createQuery\\(|nativeQuery|SELECT .*\\$|WHERE .*\\$|\\$\\{.*\\}" spring-boot/cache-caffeine --glob '*.kt' --glob '*.md'
rg -n "GenericContainer\\(|DockerImageName|Testcontainers|Launcher\\." spring-boot/cache-caffeine --glob '*.kt'
```

Repeat with `spring-boot/cache-redis` and `gatling/virtualthread-simulation`.
Expected: candidate list is module-specific.

- [ ] **Step 4: Search for bluetape4k ecosystem helpers before editing**

Run targeted searches based on the candidate class:

```bash
BLUETAPE4K_WORKSPACE=/Users/debop/work/bluetape4k
rg -n "requireNotBlank|requireNotNull|requireNotEmpty|requirePositiveNumber|requireInRange|Base58|KLogging|KLoggingChannel|MultithreadingTester|SuspendedJobTester|StructuredTaskScopeTester|untilAsserted|untilSuspending|Launcher\\." \
  spring-boot/cache-caffeine \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-projects" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-exposed" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-aws" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-image" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-javers" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-leader" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-text" \
  "$BLUETAPE4K_WORKSPACE/bluetape4k-graph" \
  --glob '*.kt'
```

Repeat with the active module directory. Expected: each raw/JDK/third-party
candidate has an adopt/borrow/skip decision.

- [ ] **Step 5: Write or adjust tests first when behavior changes**

For assertion-only refactors, use existing tests as the characterization proof.
For behavior changes, add or adjust a focused test first and run it before
implementation. Any touched caller-input validation, public error contract, or
security-sensitive boundary needs negative tests for invalid input and
redaction, or explicit review evidence proving semantics are unchanged.

Example command:

```bash
./gradlew :spring-boot-cache-caffeine:test --tests "io.bluetape4k.workshop.cache.caffeine.*" --max-workers=1 --warning-mode all --console=plain
```

Expected for new behavior tests: fail for the expected reason before implementation.

- [ ] **Step 6: Apply the minimal ecosystem refactor**

Use these transformations only when semantics match:

```kotlin
name.requireNotBlank("name")
items.requireNotEmpty("items")
count.requirePositiveNumber("count")
actual.shouldBeTrue()
collection shouldHaveSize expectedSize
private companion object : KLogging()
```

Do not change teaching-intent blocking examples unless the lesson itself improves.

- [ ] **Step 7: Classify documentation, public API, and learner impact**

For every touched public example, controller, configuration class, README
snippet, or public API:

- update `README.md` plus existing localized README files when learner-visible
  behavior or usage changes;
- add or update English KDoc when public API guidance changes;
- grep-check README/KDoc/example names against current source before claiming
  they are current;
- state supported scenario, unsupported/non-goal scenario, migration from the
  raw/JDK/third-party pattern to the bluetape4k helper, and why any raw or
  blocking pattern remains;
- confirm README/KDoc/examples do not contain real-looking tokens, passwords,
  Authorization headers, raw request bodies, native paths, or copy-pasteable
  production secrets. Local/test demo credentials must be labeled local/test-only.

- [ ] **Step 8: Run targeted compile, tests, performance, and ops checks**

Run:

```bash
./gradlew :spring-boot-cache-caffeine:compileKotlin :spring-boot-cache-caffeine:compileTestKotlin --max-workers=1 --warning-mode all --console=plain
./gradlew :spring-boot-cache-caffeine:test --max-workers=1 --warning-mode all --console=plain
git diff --check
```

Repeat with the active Wave 1 project path. For Testcontainers-backed modules,
use:

```bash
./gradlew :spring-boot-cache-redis:cleanTest :spring-boot-cache-redis:test --no-build-cache --max-workers=1 --warning-mode all --console=plain
```

Expected: commands pass before review.

Add Wave 1 performance evidence before PR creation:

- `:spring-boot-cache-caffeine`: record first-hit vs cached-hit behavior,
  request-path blocking classification, allocation risk (`N/A`, `unchanged`, or
  `changed with rationale/evidence`), and concurrency/cache-stability evidence
  from existing tests or a focused smoke test.
- `:spring-boot-cache-redis`: record first lookup, cached lookup, evict
  behavior, Redis command-count evidence where practical, or a documented
  reason plus source/test evidence when command counting is not feasible.
- `:gatling-virtualthread-simulation`: run a local Gatling/smoke simulation
  when practical, or explicitly justify skipping it with preserved-behavior
  source/test evidence.

Add Ops/SRE evidence before PR creation:

- startup/readiness or actuator health where applicable;
- log/diagnostic redaction and metric label cardinality;
- tracing/observation relevance where the module emits observations;
- `scripts/smoke-validate.sh` group evidence when the module is covered, or
  explicit "not covered" rationale.

- [ ] **Step 9: Run module-scoped 7-Tier review**

Create the row-specific review artifact from the Wave 1 table with:

```markdown
# :spring-boot-cache-caffeine Ecosystem Code Patterns Review

Date: 2026-07-04
Scope: `:spring-boot-cache-caffeine` / `spring-boot/cache-caffeine`

## Findings

| Tier | P0 | P1 | P2/P3 | Evidence |
|---|---:|---:|---|---|
| Security | 0 | 0 | ... | ... |
| Ops/SRE | 0 | 0 | ... | ... |
| Structural impact | 0 | 0 | ... | ... |
| Kotlin code quality | 0 | 0 | ... | ... |
| Tests/types/silent failure | 0 | 0 | ... | ... |
| Performance/stability | 0 | 0 | ... | ... |
| Documentation/release/evidence | 0 | 0 | ... | ... |

## Ecosystem Reuse Evidence

- Adopted:
- Preserved teaching-intent exceptions:
- Rejected alternatives:

## Security Evidence

- Auth/authz:
- Sensitive data/logs/errors:
- Injection:
- Deserialization:
- Config safe defaults:
- README/example secrets:
- Tests or source lines:

## Performance Evidence

- Hot path/blocking:
- Allocation risk:
- Contention/concurrency helper evidence:
- DB/cache/Redis command count:
- Benchmark/load/stress evidence:
- Validation command/result:

## Ops Evidence

- Startup/readiness/health:
- Logs/diagnostics/redaction:
- Metrics/tracing/cardinality:
- Smoke validation:

Final verdict: PASS, P0/P1=0.
```

Expected: P0/P1=0. If not, fix and rerun affected validation.

- [ ] **Step 10: Add a lesson when needed**

Create a lesson only when the module reveals a reusable future guard:

```markdown
# Lessons Learned - spring-boot-cache-caffeine ecosystem patterns (2026-07-04)

## L1: Prefer cache helper APIs in request-path examples

### Problem
The module had a request-path pattern that did not teach the preferred
bluetape4k cache or validation helper.

### Lesson
Future cache examples should search bluetape4k helper APIs before keeping raw
JDK or framework calls in learner-facing paths.

### Evidence
Record the changed file, targeted Gradle command, and PR number.
```

- [ ] **Step 11: Commit**

Run:

```bash
git status --short
git add spring-boot/cache-caffeine docs/review/2026-07-04-spring-boot-cache-caffeine-ecosystem-review.md
# Only when Step 10 created a lesson:
git add docs/lessons/2026-07-04-spring-boot-cache-caffeine-ecosystem-patterns.md
git diff --cached --check
git commit -m "refactor: align spring-boot-cache-caffeine ecosystem patterns" \
  -m "Use bluetape4k ecosystem helpers in the example module so the workshop teaches the preferred library patterns." \
  -m "Constraint: one PR per changed Gradle project" \
  -m "Rejected: broad repository-wide cleanup | too hard to review and validate per module" \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Directive: preserve intentional teaching examples when raw blocking demonstrates the lesson" \
  -m "Tested: ./gradlew :spring-boot-cache-caffeine:compileKotlin :spring-boot-cache-caffeine:compileTestKotlin; ./gradlew :spring-boot-cache-caffeine:test; git diff --check" \
  -m "Not-tested: unrelated modules"
```

Repeat with the active Wave 1 module's directory, review artifact, branch name,
and test commands. Expected: one module-scoped commit.

- [ ] **Step 12: Pre-PR freshness and metadata gate**

Run:

```bash
git fetch --prune origin develop
git merge-base --is-ancestor origin/develop HEAD || {
  echo "origin/develop moved; rebase or recreate the branch and rerun validation"
  exit 1
}
gh label list --json name
gh api repos/bluetape4k/bluetape4k-workshop/milestones --paginate --jq '.[] | select(.state == "open") | {number,title}'
```

Expected: branch is still based on current `origin/develop`; milestone
`backlog` and module-specific labels are available. Use `refactoring` plus a
module-specific area label such as `area:spring-boot`, `area:data-access`, or
`area:async-reactive` when the label exists. Record fallback rationale in the
PR body and matrix when a precise label does not exist.

- [ ] **Step 13: Create and locally validate PR body**

Create `/tmp/spring-boot-cache-caffeine-pr-body.md` with:

```markdown
## Summary

## What This Teaches

- bluetape4k API/pattern:
- Before/after caller behavior:
- Misuse boundary:
- Unsupported/non-production scope:

## Work Done

## Validation

## Review Notes

## DoD Status

- [ ] README/KDoc impact classified, and localized README parity handled when applicable.
- [ ] Tests and compile commands passed locally.
- [ ] Performance/security/Ops evidence recorded in the module review artifact.
- [ ] 7-Tier review is PASS with P0/P1=0.
- [ ] PR metadata and live body verified.
- [ ] CI/check state recorded, including skipped checks and local substitutes.
```

Validate the final heading before PR creation:

```bash
node - <<'NODE'
const fs = require("fs")
const body = fs.readFileSync("/tmp/spring-boot-cache-caffeine-pr-body.md", "utf8")
const headings = body.split(/\r?\n/).filter((line) => line.startsWith("## "))
if (headings.at(-1) !== "## DoD Status") {
  console.error(`Final heading is ${headings.at(-1)}`)
  process.exit(1)
}
for (const section of ["## Summary", "## What This Teaches", "## Work Done", "## Validation", "## Review Notes", "## DoD Status"]) {
  if (!headings.includes(section)) {
    console.error(`Missing ${section}`)
    process.exit(1)
  }
}
NODE
```

- [ ] **Step 14: Create PR**

Push and create PR:

```bash
git push -u origin refactor/spring-boot-cache-caffeine-ecosystem-patterns
gh pr create \
  --title "refactor: align spring-boot-cache-caffeine ecosystem patterns" \
  --body-file /tmp/spring-boot-cache-caffeine-pr-body.md \
  --assignee debop \
  --milestone backlog \
  --label refactoring \
  --label area:spring-boot
```

PR body must contain:

```markdown
## Summary

## What This Teaches

## Work Done

## Validation

## Review Notes

## DoD Status
```

No section may appear after `## DoD Status`.

- [ ] **Step 15: Verify live PR body, metadata, and checks**

Run:

```bash
PR_NUMBER=$(gh pr view --head refactor/spring-boot-cache-caffeine-ecosystem-patterns --json number -q .number)
gh pr view "$PR_NUMBER" --json headRefName,baseRefName,assignees,labels,milestone,body,statusCheckRollup,headRefOid
gh pr checks "$PR_NUMBER" --watch
```

Expected: assignee `debop`, milestone `backlog`, final body heading
`## DoD Status`, local validation recorded, and each check name/conclusion/URL
or skipped reason recorded in the PR DoD and matrix. If a path-filtered workflow
is skipped, record the targeted local compile/test/smoke evidence that replaces
that skipped check. If live body validation fails, repair it with
`gh pr edit "$PR_NUMBER" --body-file /tmp/spring-boot-cache-caffeine-pr-body.md`
before requesting review or reporting DoD.

## Task 4: Process No-Op Modules

**Files:**
- Modify: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **Step 1: Inspect the no-op candidate**

Run the three scans from Task 3 Step 3 for the project directory.

- [ ] **Step 2: Review stability and security**

Confirm:

```text
P0=0
P1=0
No unresolved race/deadlock/leak/cancellation/lifecycle/security risk
Teaching-intent exceptions are explicit
```

- [ ] **Step 3: Update the matrix**

Set:

```text
Disposition = no-op
Ecosystem reuse evidence = already uses helper OR teaching exception
Stability/security verdict = P0/P1=0 with reason
Validation evidence = source lines or scan command
```

## Task 5: Wave Boundary Verification

Run after at most three module PRs or after any shared helper change.

**Files:**
- Modify: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **Step 1: Refresh state**

Run:

```bash
git fetch --prune origin develop
gh pr list --state open --json number,title,headRefName,baseRefName,labels,milestone,assignees
worktree-list
```

- [ ] **Step 2: Run full build on the coordination branch or current synced base**

Run:

```bash
./gradlew build --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify wave performance, security, and Ops evidence**

For each performance-sensitive PR in the wave, verify the module review
artifact includes hot-path/blocking evidence, allocation risk, concurrency or
cache-stability evidence, DB/cache/Redis command-count evidence where relevant,
and benchmark/load/stress evidence or a skip rationale. Verify every security
and Ops evidence subsection is filled with source lines, test commands, PR
check URLs, or explicit not-applicable rationale.

- [ ] **Step 4: Update wave status in the matrix**

Record PR numbers, head SHAs, local validation, CI state, check skip rationale,
worktree paths, Testcontainers serial execution log, and remaining modules.

## Task 6: Final Closeout

**Files:**
- Modify: `docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md`

- [ ] **Step 1: Verify all projects are terminal**

Run:

```bash
grep -E "blocked|pending scan|pending" docs/review/2026-07-04-workshop-ecosystem-code-patterns-matrix.md
```

Expected: no rows remain blocked or pending.

- [ ] **Step 2: Verify patched and no-op row evidence**

Run a row audit over the matrix:

- every `patched` row includes PR number, live head SHA, module review artifact,
  local validation command, 7-Tier P0/P1=0, verified final `## DoD Status`, and
  latest CI/check state;
- every `no-op` row includes source/scan evidence, ecosystem reuse or teaching
  exception rationale, and stability/security P0/P1=0;
- every `follow-up` row links to a durable GitHub issue or recorded follow-up
  rationale.

- [ ] **Step 3: Run final repository build**

Run:

```bash
./gradlew build --max-workers=1 --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Final review**

Run a final 7-Tier integration review over the matrix and open PR set. P0/P1 must be 0.

- [ ] **Step 5: Non-destructive final operations inventory**

Run:

```bash
worktree-list
git branch --format='%(refname:short) %(upstream:short)'
docker ps --filter label=org.testcontainers=true --format '{{.ID}} {{.Image}} {{.Status}} {{.Names}}'
```

Expected: active worktrees/branches and any Testcontainers residue are reported.
Do not delete branches, worktrees, or containers unless cleanup is separately
requested or safety-proven.

- [ ] **Step 6: Report DoD**

Report:

```markdown
| Step | Status | Evidence |
|---|---|---|
| Registered projects classified | PASS | 100/100 matrix rows terminal |
| Module PRs | PASS | PR list |
| No-op modules | PASS | Matrix rows |
| Local validation | PASS | Final build |
| 7-Tier final review | PASS | P0/P1=0 |
| Release impact | PASS | No merge/no release performed; README/KDoc and CHANGELOG need/no-need rationale recorded |
```

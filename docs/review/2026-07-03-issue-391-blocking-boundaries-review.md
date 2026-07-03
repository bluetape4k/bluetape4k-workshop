# Issue 391 Blocking Boundary Review

## Scope

- Issue: #391 `Audit blocking sleeps and coroutine bridge boundaries`
- Work type: Type B fast-track async/reactive refactor and audit.
- Diff scope: 4 Kotlin files plus review/lesson artifacts.
- CodeReviewGraph: unavailable in this worktree, so review used direct scan, diff review, targeted compile, targeted tests, and full build.

## Scan Evidence

- Baseline `Thread.sleep(...)` direct calls: `113` Kotlin matches.
- After refactor: `106`.
- Baseline `runBlocking(...)` / `runBlocking { ... }` direct calls: `20` Kotlin matches, including `16` under `src/main`.
- After refactor: `20`, including `16` under `src/main`.
- Replaced sleep-based timing in:
  - MongoDB tailable cursor reactive test,
  - leader event listener flow/service tests,
  - Spring coroutine scope output-capture test.

## Classification

| Category | Decision |
|---|---|
| Async/test timing workaround | Replace with Awaitility or coroutine-aware subscription/start semantics when the test waits for eventual observation. |
| Scheduler coroutine bridge | Keep `runBlocking` only at Spring `@Scheduled` boundaries; document it as a blocking-to-suspend bridge and preserve `CancellationException` propagation. |
| Teaching examples | Keep explicit sleeps in virtual-thread/blocking/resilience/cache examples when the module demonstrates blocking latency, TTL, lock lease, or benchmark behavior. |
| Absence/stability checks | Prefer Awaitility `during` for no-growth windows; avoid immediate assertions that only prove the current instant. |
| Legacy or broad modules | Do not mechanically change Redisson, Okio, virtual-thread, or cache demonstrations without a focused module-specific pass. |

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| Security | PASS | No security boundary changed; all edits are test waits or documentation for scheduler bridges. |
| Stability | PASS | Sleep-based positive waits now poll actual conditions; no-growth verification uses Awaitility `during`. |
| Performance | PASS | Replaced fixed sleeps reduce unnecessary wait time in tests; production code behavior is unchanged except KDoc clarification. |
| Operator/Ops | PASS | Testcontainers-backed affected modules ran serially with `--max-workers=1`; no container launcher or CI workflow changed. |
| Developer/API | PASS | `runBlocking` remains constrained to scheduler entry points and is documented as a bridge, not a general production pattern. |
| User/Caller | PASS | Public example behavior and learner-facing runtime behavior are unchanged. |
| Evidence | PASS | Affected compile/tests passed; post-work full build passed. |

## Validation Evidence

- Pre-work local build on clean `develop`: `./gradlew build --max-workers=1 --console=plain` -> `BUILD SUCCESSFUL in 1m 35s`.
- Affected compile: `:spring-data-mongodb-coroutines:compileTestKotlin :leader-leader-election:compileTestKotlin :kotlin-coroutines:compileTestKotlin` -> `BUILD SUCCESSFUL in 18s`.
- Affected tests: `:spring-data-mongodb-coroutines:test :leader-leader-election:test :kotlin-coroutines:test` -> `BUILD SUCCESSFUL in 27s`.
- Affected compile after scheduler KDoc: `:spring-data-mongodb-coroutines:compileTestKotlin :leader-leader-election:compileTestKotlin :leader-k8s-lease-micrometer:compileKotlin :kotlin-coroutines:compileTestKotlin` -> `BUILD SUCCESSFUL in 3s`.
- Post-work full local build: `./gradlew build --max-workers=1 --warning-mode all --console=plain` -> `BUILD SUCCESSFUL in 1m 51s`.
- Whitespace check: `git diff --check` -> PASS.

## Findings

- P0/P1: 0.
- P2: Remaining sleep-heavy clusters are intentionally broad teaching/demo areas: Redisson examples, Okio pipe/cursor timing, virtual-thread blocking demonstrations, cache TTL/latency examples, and lock lease simulations.
- P3: Future work should split those clusters by module because each has different correctness criteria and replacement helpers.

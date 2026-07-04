# gatling-virtualthread-simulation Ecosystem Code Review

Date: 2026-07-04
Module: `:gatling-virtualthread-simulation`
Branch: `refactor/gatling-virtualthread-simulation-ecosystem-patterns`

## Scope

- Applied the workshop 7-Tier review lane to the Gatling virtual-thread example.
- Kept the change module-scoped: request validation, tests, README parity, and review evidence.
- Did not change the Gatling scenario shape or the virtual-thread executor demonstration.

## 7-Tier Findings

| Tier | Result | Evidence |
|---|---|---|
| Correctness | PASS | `/sync/{seconds}` and `/async/{seconds}` now validate bounded delay input before dispatching work. |
| Security | PASS | Unbounded path sleeps are rejected with 400 ProblemDetail responses. |
| Performance | PASS | `Thread.sleep(...)` remains intentional load-test behavior; validation prevents excessive sleeps. |
| Stability | PASS | Controller tests cover success and invalid delay requests for both sync and async endpoints. |
| Operations | PASS | Gatling source set compiles separately with `compileGatlingKotlin`; Gatling run still requires a running app. |
| Developer API | PASS | Delay validation is centralized in an internal helper using `requireInRange()`. |
| User/Docs | PASS | English and Korean READMEs document the 1..10 second bound and bluetape4k validation usage. |

## bluetape4k Ecosystem Usage

- `requireInRange()` from `bluetape4k-core` validates delay seconds.
- `runSuspendIO`, `httpGet`, and bluetape4k assertions remain the controller-test style.
- Existing `bluetape4k-logging`, `bluetape4k-io`, `bluetape4k-jackson3`, and `bluetape4k-coroutines` examples remain documented.

## Intentional Exceptions

- `Thread.sleep(...)` remains in `SyncTaskService` and `AsyncTaskService` because this module demonstrates blocking delay behavior under virtual threads.
- `Executors.newVirtualThreadPerTaskExecutor()` and `Executors.newThreadPerTaskExecutor(factory)` remain because they are the virtual-thread comparison surface.
- `gatlingRun` was not executed locally because the README contract requires a running application on `localhost:8080`; `compileGatlingKotlin` verifies the Gatling source set.

## Verification

```bash
./gradlew :gatling-virtualthread-simulation:compileKotlin :gatling-virtualthread-simulation:compileTestKotlin :gatling-virtualthread-simulation:cleanTest :gatling-virtualthread-simulation:test --no-build-cache --max-workers=1 --warning-mode all --console=plain
```

Result: PASS, 6 tests executed.

```bash
./gradlew :gatling-virtualthread-simulation:compileGatlingKotlin --no-build-cache --max-workers=1 --warning-mode all --console=plain
```

Result: PASS.

Observed non-blocking warnings:

- Existing root Gradle deprecation warnings for Kotlin DSL delegated properties and project dependency notation.
- Existing Gatling source-set Kotlin warnings for unresolved kotlinx.coroutines opt-in markers.

Additional local gates:

- Pattern scan for `TODO`, `FIXME`, `runBlocking`, `!!`, forbidden assertion helpers, direct `GenericContainer`, deprecated Exposed imports, and synchronized blocks: PASS
- `git diff --check`: PASS

Remaining PR gate:

- live PR metadata/body verification after PR creation

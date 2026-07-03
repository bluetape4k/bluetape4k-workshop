# Issue 391 Blocking Boundary Audit

## Context

Issue #391 followed the milestone 1.3.1 code-pattern audit. The repository still had many `Thread.sleep(...)` calls and a small number of production `runBlocking { ... }` bridges.

## Decisions

- Replace sleeps when the test is waiting for an observable asynchronous condition.
- Keep sleeps in examples that intentionally demonstrate blocking, lock lease expiry, rate limiting, cache latency, or virtual-thread behavior.
- Keep production `runBlocking` only at Spring scheduler boundaries where a blocking callback must call suspend leader work.
- Document remaining broad clusters instead of hiding them behind mechanical replacements.

## Outcome

- `Thread.sleep(...)` direct calls moved from `113` to `106`.
- `runBlocking(...)` / `runBlocking { ... }` direct calls stayed at `20`; `src/main` stayed at `16`.
- Affected tests now use Awaitility or coroutine launch semantics rather than fixed sleeps.

## Verification

- Baseline full build passed before edits.
- Affected compile passed after replacing sleeps and adding KDoc.
- Affected tests passed with `--max-workers=1`.
- Post-work full build passed with `./gradlew build --max-workers=1 --warning-mode all --console=plain`.
- `git diff --check` passed.

## Future Guard

Before changing a sleep or `runBlocking`, classify it:

- wait for async observation: use Awaitility or bluetape4k coroutine await helpers,
- scheduler bridge: keep only at the boundary and document cancellation behavior,
- teaching/demo latency: keep if the README/KDoc/test name makes the lesson clear,
- lease/TTL/no-growth window: prefer condition polling or Awaitility `during` instead of immediate assertions.

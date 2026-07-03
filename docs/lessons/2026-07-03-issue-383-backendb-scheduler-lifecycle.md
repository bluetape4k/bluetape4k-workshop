# Lessons: Issue #383 BackendB Programmatic Scheduler Lifecycle

## Context

`BackendBController` is a learner-facing example for programmatic Resilience4j decorators. It intentionally avoids annotation-based resilience on backend B and wires `TimeLimiter` and `Retry` manually for `CompletableFuture` endpoints.

## Decision

Keep the scheduler local to the controller, but make ownership explicit:

- use a named bluetape4k `NamedThreadFactory` instead of an anonymous scheduled pool;
- declare the direct `bluetape4k-core` dependency because production code now imports a core helper;
- allow tests to inject a scheduler through the constructor default parameter;
- close the scheduler through `@PreDestroy`;
- cover the shutdown contract with a focused unit test.

## Outcome

The example remains easy to follow: the programmatic decorator code still shows the same `withTimeLimiter(..., scheduler)` and `withRetry(..., scheduler)` flow, while the lifecycle boundary is now visible and verified.

## Verification

- Baseline build before work: `/tmp/issue383-baseline-build.log` — `BUILD SUCCESSFUL in 1m 17s`.
- Affected compile: `/tmp/issue383-affected-compile.log` — `BUILD SUCCESSFUL in 1s`.
- Targeted tests: `/tmp/issue383-targeted-tests-2.log` — `BUILD SUCCESSFUL in 24s`, `11 passing`.
- Full build after work: `/tmp/issue383-full-build.log` — `BUILD SUCCESSFUL in 2m 59s`.

## Future Guard

When a workshop example creates an executor, scheduler, dispatcher, client, or similar runtime resource directly, the same file should also show its lifecycle owner. Prefer a Spring-managed bean or an explicit close hook, then add a small lifecycle assertion so the example teaches cleanup as part of the pattern.

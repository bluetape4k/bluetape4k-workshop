# Issue 304 Design - Flow Metrics Sampling Workshop

## Context

Issue #304 asks for an intermediate Flow Extensions example that teaches how to
sample noisy metrics or sensor streams without building a manual scheduler. The
example belongs under `kotlin/` and should complement the existing Flow
Extensions learning path:

- `flow-extensions-search-pipeline`: debounce/session stop behavior.
- `flow-extensions-event-aggregation`: adjacent state transition analysis.
- new `flow-extensions-metrics-sampling`: throttle leading/trailing, delta
  calculation, significant-change detection, and cancellation-safe termination.

The module is a workshop consumer project. It must use the repository's
existing `bluetape4k-dependencies` BOM wiring and must not introduce new
dependencies.

## Reader Problem

A service receives high-frequency samples such as CPU utilization, queue depth,
request latency, or device sensor values. The raw stream is too dense for a
dashboard or operational log. Learners need to see the semantic difference
between:

- fast first feedback for an alert preview,
- stable trailing samples for a dashboard,
- adjacent-sample deltas for trend analysis,
- lifecycle stop signals that end collection without converting cancellation
  into a domain error.

## Decision

Add `kotlin/flow-extensions-metrics-sampling` with a small domain model and one
pipeline class:

- `MetricSample(name, value, timestamp, unit)`
- `MetricDelta(name, unit, previous, current, delta, percentChange)`
- `MetricTrend(delta, direction, significant)`
- `MetricsSamplingPipeline`

The production path uses Bluetape4k Flow extensions directly:

- `throttleLeading` for responsive first feedback.
- `throttleTrailing` for dashboard-friendly trailing samples.
- `pairwise` for adjacent deltas.
- `takeUntil` for lifecycle-bound termination.
- `Flow<T>.log()` after semantic stages, using redacted domain values.

The README uses manual timestamp/scheduler code only as the "Before" contrast.
The executable code avoids scheduler ownership, mutable last-emitted timestamp
state, or domain wrappers around `CancellationException`.

## Acceptance Criteria

1. Tests cover leading throttle behavior with deterministic virtual-time
   samples.
2. Tests cover trailing throttle behavior with deterministic virtual-time
   samples.
3. Tests cover adjacent-sample delta calculation.
4. Tests cover significant-change detection.
5. Tests cover `takeUntil` stop signal handling.
6. Tests prove collector cancellation propagates as `CancellationException` and
   upstream cleanup runs.
7. `README.md` and `README.ko.md` explain leading vs trailing throttle in a
   comparison table.
8. Both READMEs include a conventional scheduler/timestamp "Before" snippet and
   a Flow extension "After" chain.
9. Both READMEs include a `Used Bluetape4k features` table.
10. Diagrams explain the architecture and lifecycle with top-to-bottom flow,
    clear layer grouping, SVG+PNG assets, CairoSVG rendering, XML validation,
    connector audits, contact sheet, and full-size visual inspection.
11. Root README locale pair, Examples workflow, and `scripts/smoke-validate.sh`
    register the new example.
12. Local verification includes the targeted test, Kotlin compile/test compile,
    async smoke group, stale-check, README validators, workflow validation, and
    diagram checklist evidence.

## Non-Goals

- No Micrometer registry, Prometheus, Grafana, or Actuator stack.
- No database, Redis, Kafka, or external infrastructure dependency.
- No production-ready sampling engine or backpressure policy abstraction.
- No benchmark claims.

## Constraints

- Public README and KDoc are English; Korean README should be source-equivalent
  and natural.
- New data classes implement `java.io.Serializable` and define
  `serialVersionUID`.
- Validated domain construction must not expose public `copy` bypasses for
  constrained values.
- Cancellation must not be caught by broad exception handling.
- Diagram text is English and uses `Architects Daughter` / `Comic Mono`.
- Diagrams use no service icons because the new cards are code/Flow
  responsibilities, not real infrastructure services.

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Time-based tests become flaky | Use `runSuspendTest` / coroutine test virtual time and existing upstream throttle patterns; avoid real sleeps. |
| Trailing throttle semantics differ from intuition | Assert exact emitted metric names/values and explain delayed dashboard tradeoff in README. |
| Cancellation is accidentally swallowed | Do not add `catch`/`runCatching`; add a test that cancels collection and checks upstream cleanup. |
| Metric values leak sensitive labels in logs | Keep sample names short, bounded, control-character-free, and render only safe domain fields. |
| New module misses CI/smoke registration | Register root README, Examples workflow path/test/artifacts, async smoke, all-smoke, and stale-count. |
| Diagrams fail visual QA late | Create one asset at a time, render PNG with CairoSVG, run geometry/endpoint/style audits, then inspect full-size PNG and contact sheet. |

## DoD

- Spec and plan are committed before implementation.
- Tests are written RED first, then made GREEN.
- Implementation uses the required Bluetape4k Flow extensions in the executable path.
- Bilingual README and diagrams are learner-friendly and source-backed.
- Workflow/smoke/root README registration is complete.
- Tracked code-review and lesson artifacts exist before PR creation.
- PR metadata mirrors issue #304 assignee, milestone, and labels.

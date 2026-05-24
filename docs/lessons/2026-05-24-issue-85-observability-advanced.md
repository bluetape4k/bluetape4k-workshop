# Issue #85 — Observability/Performance Advanced README Enhancements

**Date**: 2026-05-24
**Branch**: `docs/issue-85-observability-advanced`
**Modules**: 5 modules updated

## Summary

Enhanced 5 module READMEs with advanced content: `Used bluetape4k Features` tables, Before/After
code comparisons, Mermaid architecture/sequence diagrams, Gatling simulation execution guides,
Virtual Thread performance comparison tables, and `withObservationSuspending` coroutine span
propagation explanations.

## Modules Updated

### 1. `observability/micrometer-observation`

Added:
- Mermaid `flowchart TD` architecture diagram showing `@Observed` AOP chain
- `observeOrNull` Before/After pattern (null-safe observation wrapper from `ObservationSupport`)
- Observation propagation across layers section (outer → service → nested span)
- Running commands (`bootRun`, `test`)

### 2. `observability/micrometer-tracing-coroutines`

Added:
- Mermaid `flowchart TD` architecture diagram (3 service types → ObservationRegistry → OTel → Zipkin)
- Mermaid `sequenceDiagram` showing coroutine span propagation across suspension points
- `CancellationException` safety section with simplified `withObservationSuspending` internals
- Trace propagation across coroutine boundaries explanation
- Prerequisites section (Docker, JDK 25, Zipkin auto-start)

### 3. `gatling/virtualthread-simulation`

Added:
- Mermaid `flowchart TD` architecture diagram (Gatling → Spring Boot → MongoDB)
- Simulation structure table (class, endpoint, injection profile, description)
- Step-by-step simulation execution guide (bootRun → gatlingRun → report)
- Report interpretation guide (key metrics, good/investigate thresholds)
- Virtual Thread impact explanation (platform vs virtual thread under 400 concurrent)
- Stop condition assertions code example
- Prerequisites section

### 4. `virtualthreads/spring-mvc-tomcat`

Added:
- Mermaid `flowchart TD` architecture diagram (Client → Tomcat → VT → bluetape4k APIs → DB)
- Virtual Thread vs Platform Thread performance comparison table
- Scenario explanation (400 concurrent DB queries, queue wait analysis)
- Gatling load testing execution guide with stop conditions
- Prerequisites section

### 5. `virtualthreads/spring-webflux`

Added:
- Mermaid `flowchart TD` architecture diagram (Netty → 4 dispatcher paths → `Dispatchers.VT`)
- Dispatcher comparison table (Default, IO, Custom 16, VT) with thread model and best-fit scenarios
- Virtual Thread vs Platform Thread performance comparison table
- Throughput comparison table (indicative numbers, 400 concurrent users)
- Gatling 4-simulation table (DefaultCoroutineSimulation, IOCoroutineSimulation, etc.)
- Step-by-step Gatling execution guide with stop conditions
- Prerequisites section

## Key Patterns Documented

### `withObservationSuspending` CancellationException Safety

The critical pattern: `CancellationException` must never be recorded as a span error.
`withObservationSuspending` handles this by rethrowing `CancellationException` before the
`obs.error(e)` call. Document and test this explicitly when using Micrometer in coroutine code.

### Span Propagation Across Coroutine Boundaries

Thread-local-based context does not survive coroutine suspension points. Micrometer's
`ObservationRegistry` uses a thread-local by default. `withObservationSuspending` solves this by
storing the observation in the coroutine context element, restoring it on resumption regardless
of which carrier thread the coroutine resumes on.

### Virtual Thread Suitability

Virtual Threads improve throughput only for I/O-bound workloads. For CPU-bound work,
`Dispatchers.Default` is still preferred. Document this distinction in all Virtual Thread READMEs
to prevent misapplication.

### Gatling Stop Conditions

Always add `.assertions()` blocks in production Gatling simulations to catch regressions:
- `global().responseTime().percentile(95.0).lt(500)` — p95 < 500ms
- `global().successfulRequests().percent().gt(99.0)` — error rate < 1%

Without these, a simulation completes "successfully" even when all requests are failing.

## Decisions

- Used Mermaid `flowchart TD` for architecture (renders in GitHub, IntelliJ, Obsidian)
- Used Mermaid `sequenceDiagram` for the coroutine span propagation sequence
- Performance numbers in comparison tables are indicative/relative, not benchmarked from this repo
- Kept Korean module description lines at the top of each README (project convention)
- All new section headers and table content are English (CLAUDE.md policy)

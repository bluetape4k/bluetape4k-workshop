# WIP Audit R2DBC WebFlux Tests

## Context

The 2026-05-18 qmd-backed workshop audit checked prior compile-drift lessons,
live GitHub issues, and current source markers before refreshing `WIP.md`.

## Decision or Finding

`spring-data/r2dbc-webflux` has service, annotated-controller, and functional
handler integration tests disabled at class level because schema initialization
was left unresolved. The module already ships `data/schema.sql`, `data/data.sql`,
and a commented `ConnectionFactoryInitializer` block, so the gap is a focused
test-restoration bug rather than a new example feature.

## Outcome

Registered GitHub issue #120 and moved it ahead of example epics in the
repo-local WIP queue.

## Verification

- `qmd query ... --no-rerank -c bluetape4k-docs` surfaced the prior workshop
  compile-drift lesson.
- `gh issue list --assignee debop` confirmed the existing open assigned queue.
- `gh issue list --search "r2dbc schema disabled tests"` found no duplicate.
- `./gradlew :spring-data-r2dbc-webflux:test --tests ...` completed with
  `BUILD SUCCESSFUL`, `0 passing`, and `44 pending`.

## Future Guidance

When a workshop module reports a green Gradle build, check whether meaningful
tests are pending or disabled before treating the example as protected.

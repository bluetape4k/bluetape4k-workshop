# Issue 331 Recommendation Explainer

## Context

Issue #331 asked for an explainable graph recommendation workshop example.
The existing `graph/recommendation` module already had the seed graph, blocking
and suspend recommendation services, TinkerGraph smoke tests, and optional
Neo4j/Memgraph integration tests.

## Decision

Extend the existing module instead of adding a new one. The new `explain*`
APIs reuse the current ranking contracts and add payloads for:

- evidence paths that created the score
- candidate exclusions such as already purchased, already followed, and self
- blocking/suspend API parity

This keeps TinkerGraph as the default no-Docker path while allowing the same
abstract tests to run against Neo4j and Memgraph.

## Outcome

`RecommendationService` and `RecommendationSuspendService` now expose:

- `explainProductRecommendations`
- `explainFollowRecommendations`

The README locale set documents the evidence payload with small tables so
learners can understand why a recommendation was made without reading the whole
graph.

## Verification

- `./gradlew :graph-recommendation:compileTestKotlin`
- `./gradlew :graph-recommendation:cleanTest :graph-recommendation:test --no-build-cache`
  - 74 tests, 0 failures, 0 skipped
- `./gradlew :graph-recommendation:integrationTest --no-build-cache`
  - 148 tests, 0 failures, 0 skipped

## Future Guard

When recommendation examples add explanation payloads, test the plain ranking
API and the explainable API together. Otherwise the learner-facing explanation
can drift from the ranking code.

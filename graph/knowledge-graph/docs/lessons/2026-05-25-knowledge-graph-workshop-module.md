# Lesson: graph-knowledge-graph Workshop Module

**Date**: 2026-05-25
**Branch**: feat/issue-11-knowledge-graph
**Issue**: bluetape4k-workshop #11
**PR**: #202

## Summary

Added the `graph/knowledge-graph` module — a technology-domain knowledge graph example
built on bluetape4k-graph. Followed the `social-network` module as the implementation blueprint.

## Decisions

### Schema design
- Three vertex types: Entity (technologies), Concept (vocabulary), Document (sources)
- Three edge types: MENTIONS (Doc→Entity, with confidence score), RELATED_TO (Entity→Entity, typed), IS_A (Entity→Concept)
- Intentionally simpler than social-network: no bidirectional edges, no compound properties

### Service design
- `KnowledgeGraphService` (blocking) + `KnowledgeGraphSuspendService` (Flow-based)
- `MAX_TRAVERSAL_DEPTH = 10` as a named constant shared via companion object
- `findRelatedEntities` and `inferRelationshipPaths` both validate depth/maxDepth against the constant

### Test structure
- TinkerGraph tests: no Docker, default `:test` task
- Neo4j + Memgraph: `@Tag("integration")`, `:integrationTest` task
- Seed dataset: technology-domain (Kotlin, Spring, Coroutines, JVM) with 2 docs + 4 entities + 4 concepts

## Findings from Code Review (Step 6-R)

### HIGH issues found and resolved

| ID | Issue | Fix |
|----|-------|-----|
| H-1 | `findRelatedEntities` `depth` param not validated | Added `depth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "depth")` |
| H-2 | `inferRelationshipPaths` `maxDepth` param not validated | Added `maxDepth.requireInRange(1, MAX_TRAVERSAL_DEPTH, "maxDepth")` |
| H-3 | Zero validation tests for 14 validation guards | Added 8 validation tests per abstract class (blank ID, out-of-range confidence, zero depth, zero maxDepth/maxPaths) |
| H-4 | Suspend test missing `addEntity + findRelatedEntities` round-trip | Added the missing test to `AbstractKnowledgeGraphSuspendTest` |

### Root cause
Validation was applied to string inputs and `confidence` but missed numeric traversal parameters.
The pattern in `SocialNetworkService` validates `maxDegree` — same pattern should have been applied here from the start.

## Future Guidance

1. **Always validate ALL numeric parameters** — not just strings. When a method accepts `depth`, `maxDepth`, `maxPaths`, `limit`, or any numeric bound, add `requireInRange` or `requirePositiveNumber` immediately.
2. **Mirror test count across blocking/suspend abstract classes** — if blocking abstract has N tests, suspend must have N tests. Check counts after writing.
3. **Invoke `bluetape4k-workflow` skill before starting work** — not after. The workflow ensures Step 6-R and Step 7 run in the correct order.
4. **Lessons file must be committed before PR creation** — if forgotten, create a fixup commit on the feature branch before requesting merge.

## Test Results

```
./gradlew :graph-knowledge-graph:test
TinkerGraph blocking:  19 tests, 0 failures
TinkerGraph suspend:   19 tests, 0 failures
BUILD SUCCESSFUL
```

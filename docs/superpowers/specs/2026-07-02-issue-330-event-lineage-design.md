# Issue #330 Event Lineage Design

## Context

Issue #330 asks for an advanced graph workshop example that models business
event lineage and audit reconstruction. Existing graph examples cover social
relationships, knowledge graphs, recommendation paths, abuse detection, and
`graph-io-pipeline` import/export. The new example must not duplicate #287
`graph-io-pipeline`; it should teach why a state exists and which upstream event,
actor, or decision caused it.

Current source evidence:

- `graph/io-pipeline` teaches CSV, Jackson3 NDJSON, GraphML import/export and
  report checks.
- `graph/social-network`, `graph/recommendation`, `graph/knowledge-graph`, and
  `graph/abuser-detection` use direct `GraphOperations` domain services over
  TinkerGraph for default tests.
- `exposed/javers-approval-workflow` and `exposed/javers-persistence-audit`
  already teach JaVers approval and persistence audit history. This module will
  reference audit-table and JaVers use cases in README prose, but it will not
  embed JaVers or database persistence.
- `bluetape4k-graph` PR #275 already added a data catalog lineage example for
  dataset/table/column/job/dashboard impact. This workshop module must use
  business events, aggregates, actors, decisions, and audit trail questions
  instead of data asset lineage.

## Goal

Create `graph/event-lineage` as a deterministic TinkerGraph workshop module that
lets learners answer:

1. Which upstream event caused this aggregate state?
2. Which actor or decision approved the state transition?
3. Which later events superseded an earlier event?
4. What audit trail explains a current aggregate state?
5. What happens when a required lineage link is missing?

## Scope

In scope:

- New Gradle module `:graph-event-lineage`.
- Blocking service built on `GraphOperations` and TinkerGraph.
- Domain schema for `Event`, `Aggregate`, `Actor`, and `Decision` vertices.
- Edge labels for `EMITS`, `CAUSED_BY`, `APPROVED_BY`, and `SUPERSEDES`.
- Deterministic seed fixture in Kotlin test code.
- Tests for graph construction, lineage path queries, audit trail
  reconstruction, superseding event chains, and missing-link behavior.
- `README.md` and `README.ko.md` explaining graph lineage versus ordinary audit
  tables and JaVers-style object history.
- README architecture and sequence diagrams with SVG and PNG assets.
- Root README/README.ko, repo-local `AGENTS.md`, smoke script, and Examples
  workflow registration.

Out of scope:

- No graph-io CSV/NDJSON/GraphML import/export.
- No JaVers repository integration or database-backed audit store.
- No Neo4j/Memgraph integration tests in this first workshop slice.
- No production event store, outbox relay, or authorization model.

## Design

### Module Shape

`graph/event-lineage` follows the existing graph domain example pattern:

- `schema/EventLineageSchema.kt` defines labels and property names.
- `model/AuditTrail.kt` defines serializable reader-facing query results.
- `service/EventLineageService.kt` owns graph lifecycle, idempotent vertex
  creation, edge creation, and bounded traversal methods.
- `src/test/.../seed/EventLineageSeed.kt` creates a small deterministic order
  approval scenario.
- `EventLineageTinkerGraphTest` runs default tests against in-memory
  `TinkerGraphOperations`.

### Graph Model

Vertices:

- `Event`: `eventId`, `type`, `occurredAt`, `summary`
- `Aggregate`: `aggregateId`, `aggregateType`, `state`, `version`
- `Actor`: `actorId`, `displayName`, `role`
- `Decision`: `decisionId`, `decisionType`, `status`, `reason`

Edges:

- `EMITS`: aggregate -> event
- `CAUSED_BY`: event -> upstream event
- `APPROVED_BY`: event -> decision
- `DECIDED_BY`: decision -> actor
- `SUPERSEDES`: event -> previous event

`DECIDED_BY` is included because issue #330 needs actor nodes and
`APPROVED_BY` connects an event to a decision, not directly to an actor. This
keeps approval decision evidence explicit.

### Query Contract

The service exposes source-backed methods:

- `eventsForAggregate(aggregateId)` returns emitted events in deterministic
  timestamp/id order.
- `causalPath(eventId, rootEventId, maxDepth)` returns a bounded path from a
  current event back to a root cause.
- `auditTrailForAggregate(aggregateId)` reconstructs emitted events, causal
  roots, approval decisions, and deciding actors for a current aggregate.
- `supersededChain(eventId)` follows `SUPERSEDES` edges from newest event to
  previous events.
- `missingCausalLinks(aggregateId)` returns emitted events that have neither
  root-cause nor upstream cause evidence.

Missing unknown vertices return empty results instead of exceptions. Invalid
blank IDs fail fast with bluetape4k validation helpers.

### Diagrams

Two README diagrams are required:

- Architecture: static ownership view showing domain events, aggregate state,
  actor/decision evidence, graph service, and TinkerGraph backend. It must
  include layer boundaries and a legend for edge semantics if connector styles
  differ.
- Sequence: audit reconstruction request over actual participants:
  caller -> service -> graph -> aggregate/events -> decisions/actors -> report.
  It must follow the current best-practices sequence style with numbered labels,
  transparent `alt` frames, muted palette, and marker/color parity.

## Risks And Mitigations

| Risk | Mitigation |
|---|---|
| Duplicating data lineage #275 or graph-io #287 | Keep the domain business-event/audit-trail focused and do not add graph-io fixtures. |
| Turning the example into a production audit store | README states graph lineage complements, not replaces, ordinary audit tables and JaVers snapshots. |
| Query ordering becomes nondeterministic | Sort reader-facing results by timestamp/id and assert exact output in tests. |
| Graph traversal loops on bad data | Bounded BFS with visited-path checks; tests include superseding chain and missing-link behavior. |
| Diagram QA regressions | Use current best-practices references, render PNG with CairoSVG, run repo-local diagram QA plus full-size visual inspection. |

## Acceptance Criteria Mapping

| Issue criterion | Design response |
|---|---|
| Uses root `bluetape4k-dependencies` BOM only | Module declares versionless aliases from `libs.versions.toml`; no module-specific BOM. |
| Tests verify graph construction | Seed test asserts vertex/edge counts and properties. |
| Tests verify lineage path queries | `causalPath` and `auditTrailForAggregate` tests assert ordered path content. |
| Tests verify missing-link behavior | `missingCausalLinks` and unknown ID tests assert deterministic empty/missing results. |
| README explains graph lineage vs audit tables | README/README.ko include comparison section and JaVers/data-table boundary. |
| Does not duplicate #287 graph-io import/export | No graph-io dependencies or CSV/NDJSON/GraphML workflows. |

## DoD

- `./gradlew :graph-event-lineage:test --no-build-cache --rerun-tasks --console=plain` passes.
- `./gradlew :graph-event-lineage:compileKotlin :graph-event-lineage:compileTestKotlin --warning-mode all --console=plain` passes.
- `./gradlew projects --console=plain` lists `:graph-event-lineage`.
- `./scripts/smoke-validate.sh all-smoke` includes and passes the new module.
- `./scripts/smoke-validate.sh stale-check` reports expected module count and no broken README image links.
- `./scripts/smoke-validate.sh diagram-qa` passes with concrete changed-diagram evidence.
- `actionlint .github/workflows/Examples.yml` passes after workflow edits.
- `git diff --check` passes.
- Step 6-R review records `P0=0`, `P1=0`.

# Issue 348 - Kafka-first outbox fallback

## Context

Issue #348 added a workshop module that lowers hot transaction write cost by
storing only `orders` in the transaction, publishing the event to Kafka after
commit, and writing `event_publications` only when direct publication fails or
reconciliation repairs a missing row.

## Decision

The module intentionally does not implement the classic transactional outbox as
the primary path. Direct Kafka success leaves no publication row. Failure,
timeout, disabled publish, and reconstructed gaps create `NOT_PUBLISHED` rows
that the relay can claim and re-drive.

## What Failed During The Work

- Rounded connector paths in the architecture diagram passed visual inspection
  but failed geometry audits when Q-bend control points collapsed into
  zero-length pre/post legs.
- The architecture validator only parsed `M`/`L` path points, so valid Q-bend
  rounded connectors were reported as diagonal false positives.

## Evidence That Resolved It

- `diagram-geometry-audit.py`: `geometry_failures=0` for the touched
  architecture and state diagrams.
- `diagram-mixed-corner-audit.py`: `q_bends=10 failures=0`.
- `validate-readme-architecture-diagrams.mjs`: `checked=99`, `failures=0`
  after parsing Q control/end points as route waypoints.
- `validate-sequence-diagrams.mjs`: `checked=74`, `failures=0`.
- Full-size PNG inspection confirmed no card/label/connector overlap in the
  architecture, sequence, and state diagrams.

## Future Guard

For connector-heavy README diagrams, do not accept "looks good" until the Q bend
geometry, mixed-corner, endpoint, XML, CairoSVG render, and full-size PNG checks
all pass. If a repo-local validator cannot parse rounded connectors, fix the
validator narrowly instead of weakening the diagram geometry.

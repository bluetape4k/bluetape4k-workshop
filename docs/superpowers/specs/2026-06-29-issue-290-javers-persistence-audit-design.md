# Issue #290 - JaVers Persistence Audit Workshop Design

## Context

`exposed/javers-audit` teaches the smallest Exposed + JaVers boundary with an
in-memory JaVers repository. Issue #290 needs a persistence-backed audit example
that shows the operational boundary between the current relational row and a
durable JaVers snapshot store.

## Source Evidence

- `exposed/javers-audit` stores only the current `ProductTable` row in Exposed
  and commits immutable `Product` values to an in-memory JaVers repository.
- The workshop catalog already exposes `bluetape4k-javers-persistence-redis`
  and `bluetape4k-javers-persistence-kafka` aliases.
- `bluetape4k-javers/javers-persistence-redis` provides read-capable
  `LettuceCdoSnapshotRepository` and `RedissonCdoSnapshotRepository`.
- `bluetape4k-javers/javers-persistence-kafka` is intentionally write-only;
  historical reads require `KafkaCdoSnapshotProjector` into a read-capable
  repository such as Redis.
- Workshop Redis tests already use `RedisServer.Launcher.redis` and
  `RedisServer.Launcher.RedissonLib`.

## Decision

Add `exposed/javers-persistence-audit` as a focused module instead of extending
`exposed/javers-audit`.

Use Redis/Redisson as the implemented persistence backend because it supports
commit persistence, latest snapshot, history, and diff queries in one learner
path. Document Kafka as the event-stream/follow-up variant because the Kafka
repository publishes snapshots but does not answer history queries by itself.

## Module Contract

- Exposed owns the current `orders` row.
- Redis-backed JaVers owns durable audit snapshots.
- `OrderAuditService.place()` commits an INITIAL snapshot before writing the
  current row.
- `OrderAuditService.markPaid()` commits an UPDATE snapshot before
  materializing the paid row.
- `OrderAuditService.delete()` commits a TERMINAL snapshot before removing the
  current row.
- `OrderAuditService.getHistory()` returns snapshots oldest-first for README
  readability.
- `OrderAuditService.getLatestSnapshot()` returns the latest JaVers snapshot or
  `null`.
- `OrderAuditService.diff()` compares two immutable order values without
  writing to Exposed or Redis.

## Failure Contract

If the JaVers repository cannot encode or persist a snapshot, the service must
surface the failure instead of silently accepting an unaudited write. Tests cover
the failure at the audit sink boundary.

## Diagram Contract

Create at least two README diagrams:

- Architecture: static ownership/dependency view with Exposed current row,
  Redis JaVers snapshot repository, and Kafka write-only/projection boundary.
- Write-order flow: mutation guard, Redis-backed commit/read path, and sink
  failure behavior.

All diagrams must follow `$bluetape4k-diagram`: English labels, `Architects
Daughter` and `Comic Mono`, catalog icons for Redis/Kafka/database, CairoSVG
SVG-to-PNG rendering, helper audits, contact sheet, and full-size PNG visual
inspection.

## Out of Scope

- Rewriting `exposed/javers-audit`.
- Implementing a full Kafka projection consumer in the first iteration.
- Adding a new dependency version outside the root BOM/catalog.

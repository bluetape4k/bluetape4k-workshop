# Issue #104 - Multi-Tenant Data Isolation Workshop Design

**Date**: 2026-05-24
**Issue**: https://github.com/bluetape4k/bluetape4k-workshop/issues/104
**Parent Epic**: #76
**Backlog tracker**: #92
**Status**: Draft

## Goal

Add an advanced workshop example that proves tenant-scoped data isolation across repository queries, cache keys, lock keys, rate-limit buckets, and metrics tags.

## Scope

- New Spring Boot workshop module: `spring-boot/multi-tenant-data-isolation`.
- Tenant-aware Exposed JDBC repository over an H2 database.
- Baseline repository/cache paths that intentionally omit tenant scope and demonstrate leakage risk in tests.
- Tenant-prefixed cache keys, lock keys, and rate-limit bucket keys.
- Tenant-tagged Micrometer counters.
- README with Bluetape4k-first explanation and feature table.

## Non-Goals

- Production authentication or authorization.
- Real Redis-backed distributed locks.
- Full HTTP API security integration.
- Changing shared dependency governance.

## Design

The module keeps the example small and deterministic:

1. `TenantId` represents a normalized tenant identifier.
2. `InvoiceTable` stores `tenant_id` on every row.
3. `TenantInvoiceRepository` implements the bluetape4k Exposed `LongJdbcRepository` pattern and adds tenant-scoped methods.
4. `UnsafeInvoiceRepository` uses the same table without tenant predicates to provide a failing baseline scenario.
5. `TenantKeyFactory` builds cache, lock, and rate-limit keys with the tenant prefix.
6. `TenantInvoiceService` composes repository, cache, lock, rate-limit, and metrics behavior.
7. Workshop record/data classes implement `Serializable` and define `serialVersionUID`.
8. Lock behavior uses per-key in-memory locks, and rate-limit behavior uses fixed-window in-memory buckets keyed by `TenantKeyFactory`; real distributed lock semantics and Bucket4j adapters are explicitly out of scope for this module.

## Used Bluetape4k Features

| Feature | Module/artifact | Code reference | Benefit |
|---|---|---|---|
| Exposed repository helper | `io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc` | `TenantInvoiceRepository : LongJdbcRepository` | Reuses bluetape4k repository defaults and keeps Exposed row mapping explicit. |
| Spring Boot support | `io.github.bluetape4k:bluetape4k-spring-boot4-core` | module dependencies | Keeps the example aligned with Bluetape4k Spring Boot 4 workshop modules. |
| Logging | `io.github.bluetape4k:bluetape4k-logging` | service/repository companion loggers | Uses existing logging conventions. |
| Metrics bridge | `io.github.bluetape4k:bluetape4k-micrometer` | tenant-tagged `MeterRegistry` counters | Shows tenant-aware observability without custom metrics wrappers. |
| Test runtime | `io.github.bluetape4k:bluetape4k-junit5` | module test dependencies | Keeps the new module aligned with shared test lifecycle rules. |
| Test assertions | `io.github.bluetape4k:bluetape4k-assertions` | isolation tests | Keeps tests consistent with the ecosystem. |

## Acceptance Criteria

- Baseline leakage test shows cross-tenant data can leak when tenant scope is omitted.
- Tenant-scoped repository reads and writes cannot cross tenant boundaries.
- Cache hits are isolated per tenant.
- Lock/rate-limit key examples include tenant prefixes and use in-memory state keyed by those prefixes.
- Metrics include a tenant tag.
- README includes a `Used Bluetape4k features` table and before/after explanation.
- Targeted Gradle test for the new module passes.

## Risks

- Real distributed lock semantics are out of scope; use per-key `ReentrantLock` instances to demonstrate tenant-safe keying.
- Bucket4j is out of scope for this module; use an in-memory fixed-window limiter so the example does not add rate-limit dependencies only for key naming.
- H2 auto-increment behavior must be verified with Exposed 1.3 APIs.

## Review Notes

- Claude advisor gate 1: `.omx/artifacts/claude-issue-104-design-20260524152918.md`
- P0/P1 findings from gate 1: fixed in this draft.
- Claude advisor gate 2: `.omx/artifacts/claude-issue-104-design-rerun-20260524153255.md`
- Gate 2 verdict: PASS, P0=0, P1=0.

# Issue #80 — Data Access Advanced README Strengthening

**Date**: 2026-05-24
**Issue**: #80 — Data Access Advanced 예제 강화

## Summary

Strengthened READMEs for 6 data access modules with bluetape4k feature tables,
Before/After code comparisons, and Mermaid architecture diagrams.

## Scope Adjustment (Important)

The original issue listed 8 modules including 4 that were **deleted in Issue #97**
(`dao-web-transaction`, `spring-transaction`, `sql-web-virtualthread`, `sql-webflux-coroutines`).
These were replaced by `mvc-jdbc`, `mvc-virtualthread`, and `webflux-r2dbc`.

**Actual scope (6 modules):**

| Module | Action |
|--------|--------|
| `exposed/mvc-jdbc` | Added Mermaid `sequenceDiagram` (already had feature table + before/after from #97) |
| `exposed/mvc-virtualthread` | Added Mermaid, enhanced feature table with code locations, added Before/After |
| `exposed/webflux-r2dbc` | Added Mermaid, enhanced feature table with code locations, added Before/After |
| `spring-data/r2dbc-coroutines` | Added English title, added Mermaid (already had full bt feature table + before/after) |
| `spring-data/r2dbc-webflux` | Added Mermaid, added full bt feature table, added Before/After |
| `spring-data/r2dbc-webflux-exposed` | Added Mermaid, added full bt feature table, added Before/After |
| `exposed/javers-audit` | Skipped — already complete from Issue #100 |

## Key Patterns Documented

### `R2dbcRepository` (bluetape4k-exposed-r2dbc)

`spring-data/r2dbc-webflux-exposed` uses `R2dbcRepository<ID, Entity>` as a base class,
giving `findAll()`, `findById()`, `count()`, `deleteById()` for free.
Only custom operations (`upsert`, `findByEmail`) need implementation.

### `*Suspending` extensions (bluetape4k-spring-boot4-r2dbc)

`spring-data/r2dbc-coroutines` uses `R2dbcEntityOperations.*Suspending` wrappers
that convert Mono/Flux operations to suspend functions, eliminating `awaitSingle()` chains.

### `virtualFuture` + `ShutdownQueue` (bluetape4k-virtualthread-api)

`exposed/mvc-virtualthread` submits all blocking JDBC work through `virtualFuture(executor) { }`
instead of `@Transactional`, avoiding carrier thread pinning.
`ShutdownQueue.register(executor)` provides zero-boilerplate graceful shutdown.

### `suspendTransaction` (bluetape4k-exposed)

`exposed/webflux-r2dbc` wraps all Exposed R2DBC operations in `suspendTransaction(db)`,
ensuring coroutine-safe transaction boundaries and Flow stream compatibility.

## Lessons

- Always check whether prior issues (e.g. #97) have already deleted/replaced modules
  mentioned in a new issue's scope. Verify `git log --all --stat -- <path>` before writing.
- `exposed/javers-audit` was already well-documented by Issue #100; avoid duplicate work.
- `spring-data/r2dbc-webflux` used `CoroutineCrudRepository` directly — no bluetape4k
  repository base class, but `KLoggingChannel` and coroutine-first service layer still
  count as bluetape4k usage.
- Mermaid `sequenceDiagram` is the clearest format for layered HTTP→Service→Repo→DB flows.

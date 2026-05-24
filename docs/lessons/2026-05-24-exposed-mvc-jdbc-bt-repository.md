# Lessons — exposed-mvc-jdbc BT Repository Strengthening

**Date**: 2026-05-24  
**Issue**: #79 (Data Access Basic)  
**Type**: Type-B Fast Track  
**Branch**: `feat/issue-79-data-access-basic`

---

## Root Cause / Motivation

`exposed-mvc-jdbc` had `bluetape4k-exposed-core` and `bluetape4k-exposed-jdbc` on the classpath
but used none of their APIs — tables extended plain `Table`, repositories had ~35 lines of manual
CRUD per class with no pagination, batchInsert, or audit support.

---

## Decisions

### AuditableLongIdTable for AuthorTable

Chose `AuditableLongIdTable` (not plain `LongIdTable`) because:
- Authors are a user-managed resource — audit trail (who created/updated) has real value
- The 4 audit columns are wired automatically via `clientDefault` and `defaultExpression` — zero
  application code needed at insert time
- `UserContext` defaults to `"system"` when no explicit context is set, so existing seed code
  requires no change

### LongIdTable for BookTable (no audit)

Kept BookTable without audit to show the contrast and to avoid adding audit columns to a
secondary entity in a Basic example.

### LongAuditableJdbcRepository / LongJdbcRepository

These interfaces inherit the full CRUD surface:
- `findAll()`, `findById()`, `findByIdOrNull()`, `count()`, `existsById()`
- `deleteById()`, `deleteAll()`, `deleteAllByIds()`
- `findPage()` with `ExposedPage` (offset pagination)
- `batchInsert()`, `batchUpsert()`
- `auditedUpdateById()` (AuditableJdbcRepository only) — auto-sets `updatedAt`/`updatedBy`

The repository implementation drops from ~35 lines to ~20 lines (including KDoc).

### `exposed-java-time` missing dependency (bug found during implementation)

`AuditableIdTable` uses `org.jetbrains.exposed.v1.javatime.*` for `timestamp()` columns.
The `bluetape4k-exposed-core` POM declares this as a `compileOnly` dependency to avoid forcing
the choice between `exposed-java-time` and `exposed-kotlin-datetime`. Downstream modules must
add `jetbrains.exposed.java.time` explicitly.

**Pattern**: if a BT module's table base class uses date/time columns, always add
`jetbrains.exposed.java.time` (or `exposed-kotlin-datetime`) to the module's build.gradle.kts.

---

## Verification

```
Tests: 11 passing (AuthorControllerTest, BookController, ProductControllerTest,
                   OrderControllerTest, PlaceOrderRollbackTest, ConcurrentPlaceOrderTest)
./gradlew :exposed-mvc-jdbc:test — BUILD SUCCESSFUL
```

---

## Future Guidance

1. **`exposed-java-time` must be explicit**: do not rely on transitive classpath for javatime.
   Add it when any BT auditable table is used.

2. **`findById()` is non-null in JdbcRepository**: it throws `NoSuchElementException` if absent.
   Use `findByIdOrNull()` when null is a valid outcome, not the Elvis `?: throw` pattern.

3. **vararg predicate syntax**: `findBy({ predicate })` — explicit parentheses required around
   the lambda when other named parameters follow the vararg in the signature.

4. **EntityID<Long> propagation**: when `AuthorTable` becomes `LongIdTable`/`AuditableLongIdTable`,
   `insertAndGetId` returns `EntityID<Long>`. FK columns in child tables are `Column<EntityID<Long>>`,
   so insert statements accept `EntityID<Long>` directly — no `EntityID(raw, table)` wrapper needed
   for insertAndGetId return values, but manual Long → EntityID wrapping is needed when inserting
   from a plain Long (e.g., `it[authorId] = EntityID(req.authorId, AuthorTable)`).

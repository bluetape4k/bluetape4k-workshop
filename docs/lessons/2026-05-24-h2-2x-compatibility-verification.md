# H2 2.4.x Compatibility Verification (Issue #146)

**Date**: 2026-05-24  
**Branch**: fix/issue-146-h2-compat  
**Scope**: `vertx/vertx-sqlclient`, `vertx/coroutines`, `redis/redisson-examples`, `spring-boot/chaos-monkey`

## Root Cause (Original Issue)

H2 2.x introduced stricter SQL compatibility compared to H2 1.x:
- Unquoted identifiers are stored and compared in UPPERCASE by default
- MySQL backtick quoting (`` `column_name` ``) is not supported without `MODE=MySQL`
- `AUTO_INCREMENT` still works (MySQL compatibility option)

## Finding: Modules Were Already Compatible

Verification confirmed all affected modules already have correct H2 2.x settings:

| Module | H2 URL / Settings | Status |
|--------|-------------------|--------|
| `vertx/vertx-sqlclient` | `MODE=MYSQL;DATABASE_TO_UPPER=FALSE` | ✅ Already fixed |
| `vertx/coroutines` | `MODE=MySQL` | ✅ Already fixed |
| `redis/redisson-examples` (JdbcConfigTest) | Spring-managed H2 URL, standard SQL | ✅ Passes |
| `spring-boot/chaos-monkey` | `jdbc:h2:mem:testdb` (JPA auto DDL) | ✅ Passes |

## Pre-existing Fory Failure (Unrelated to Issue)

`redis-redisson-examples` showed 38 failing tests with:
```
java.lang.NoSuchMethodError: 'org.apache.fory.ThreadSafeFory
  org.apache.fory.config.ForyBuilder.buildThreadSafeForyPool(int, int, long, java.util.concurrent.TimeUnit)'
```

**Root cause**: The `fix/issue-146-h2-compat` worktree was created when develop used
`bluetape4k = "1.8.0-SNAPSHOT"`. Since then, develop moved to `1.9.1` where `ForyBuilder`
API changed. These failures are NOT related to H2 2.4.x.

## Correct H2 2.x URL Patterns

```kotlin
// vertx-sqlclient style: MySQL mode + case-insensitive
jdbcUrl = "jdbc:h2:mem:test;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=FALSE;"

// R2DBC style: case-insensitive identifiers (fixes Spring Data R2DBC quoting)
url: r2dbc:h2:mem:///pocdb?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE

// JPA / JDBC style: H2 auto-handles DDL, no extra options needed for simple schemas
spring.datasource.url=jdbc:h2:mem:testdb
```

## Key Decision

`CASE_INSENSITIVE_IDENTIFIERS=TRUE` is preferred over `DATABASE_TO_UPPER=FALSE` for R2DBC
because Spring Data R2DBC H2 dialect quotes column names in uppercase (`"ID"`, `"NAME"`),
while `@Table("users")` produces lowercase `"users"`. Case-insensitive mode resolves both
without requiring schema DDL changes.

## Future Guidance

- When creating a new module using H2 in-memory DB with Spring Data R2DBC:
  append `CASE_INSENSITIVE_IDENTIFIERS=TRUE` to the R2DBC URL
- When using Vert.x SQL client or JDBC with H2:
  use `MODE=MySQL` or `DATABASE_TO_UPPER=FALSE` depending on SQL dialect
- Do NOT use MySQL backtick quoting (`` `col` ``) without `MODE=MySQL`

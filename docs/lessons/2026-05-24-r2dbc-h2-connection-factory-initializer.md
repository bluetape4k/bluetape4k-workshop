# R2DBC WebFlux H2 Test Re-enablement (Issue #120)

**Date**: 2026-05-24  
**Branch**: fix/issue-120-r2dbc-webflux-tests  
**Module**: `spring-data/r2dbc-webflux`

## Root Cause

Three test classes (`UserServiceTest`, `UserHandlerIT`, `UserControllerTest`) were
disabled with `@Disabled` due to H2 2.x SQL compatibility failures. The original
`spring.sql.init` approach was unreliable with R2DBC embedded databases under Spring Boot 4.

Three separate problems were compounding:

### Problem 1: `spring.sql.init` not executing schema/data SQL

`spring.sql.init.schema-locations` / `data-locations` do not reliably fire for
R2DBC embedded databases in Spring Boot 4. The schema was never created, causing
all tests to fail with "Table not found" errors.

**Fix**: Replace with an explicit `ConnectionFactoryInitializer` bean that uses
`CompositeDatabasePopulator` to run schema.sql then data.sql sequentially.

```kotlin
@Bean
fun databaseInitializer(connectionFactory: ConnectionFactory): ConnectionFactoryInitializer {
    return ConnectionFactoryInitializer().apply {
        setConnectionFactory(connectionFactory)
        setDatabasePopulator(
            CompositeDatabasePopulator().apply {
                addPopulators(ResourceDatabasePopulator(ClassPathResource("data/schema.sql")))
                addPopulators(ResourceDatabasePopulator(ClassPathResource("data/data.sql")))
            }
        )
    }
}
```

### Problem 2: MySQL backtick quoting in data.sql

H2 2.x does not support MySQL backtick identifier quoting without `MODE=MySQL`.
The original `data.sql` used `` INSERT INTO users(`name`, `login`, ...) ``.

**Fix**: Remove backticks — use plain unquoted column names.

```sql
-- Before (fails on H2 2.x without MODE=MySQL):
INSERT INTO users(`name`, `login`, `email`, `avatar`) VALUES ...

-- After:
INSERT INTO users(name, login, email, avatar) VALUES ...
```

### Problem 3: Spring Data R2DBC H2 dialect identifier case mismatch

Spring Data R2DBC H2 dialect quotes column names as uppercase (`"ID"`, `"NAME"`, `"EMAIL"`),
while `@Table("users")` generates lowercase `"users"`. H2 2.x stores unquoted identifiers
as uppercase (`USERS`), so `"users"` ≠ `USERS`.

**Fix**: Add `CASE_INSENSITIVE_IDENTIFIERS=TRUE` to the R2DBC URL. This makes H2 compare
all identifiers case-insensitively, resolving the mismatch between Spring Data's quoted
uppercase columns and schema's unquoted lowercase definitions.

```yaml
spring:
  r2dbc:
    url: r2dbc:h2:mem:///pocdb?options=DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
```

### Problem 4 (bonus): Missing `.bodyValue()` in update test

`UserControllerTest.update non-existing user` sent a PUT request without a request body,
causing Spring to return 400 (missing `@RequestBody`) instead of the expected 404.

**Fix**: Add `.bodyValue(userToUpdate)` to the request chain.

## Outcome

44/44 tests passing after fix. All `@Disabled` annotations removed.

## Decision Record

- `CASE_INSENSITIVE_IDENTIFIERS=TRUE` was chosen over `DATABASE_TO_UPPER=FALSE` because
  the latter prevents Spring Data from using uppercase column aliases in `@Query` results.
- `ConnectionFactoryInitializer` was chosen over `spring.sql.init` because it is explicitly
  ordered in the Spring context lifecycle and guaranteed to run before test setup.

## Future Guidance

When setting up a new Spring Data R2DBC + H2 in-memory test module:

1. Use `ConnectionFactoryInitializer` bean for schema/data initialization (not `spring.sql.init`)
2. Add `CASE_INSENSITIVE_IDENTIFIERS=TRUE` to H2 R2DBC URL
3. Avoid MySQL backtick quoting in SQL files unless `MODE=MySQL` is set
4. Verify `@Query` using lowercase column names still resolves with CASE_INSENSITIVE mode

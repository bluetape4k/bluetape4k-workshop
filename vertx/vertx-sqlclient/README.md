# Vert.x SQL Client

[한국어](README.ko.md) | English

This module is a test-driven Vert.x SQL Client workshop. It compares direct pool
queries, coroutine transaction helpers, SQL templates, and Vert.x data-object
mapping on top of H2 and a MySQL Testcontainers runtime.

## Architecture

![Vert.x SQL client architecture](../../docs/images/readme-diagrams/vertx-vertx-sqlclient-readme-architecture-01.png)

## Example Areas

| Area | Source | What it demonstrates |
|---|---|---|
| JDBC pool queries | `JDBCPoolExamples` | `Pool.query`, `preparedQuery`, `coAwait()`, `withSuspendTransaction` |
| SQL templates | `SqlClientTemplateExamples` | named parameters, row mappers, tuple mappers, JSON and data-class binding |
| Data objects | `DataObjectMappingExamples` | generated `@RowMapped` mapper for `UserDataObject` |
| Runtime fixture | `AbstractSqlClientTest` | H2 pool setup and reusable MySQL 8 Testcontainers connection options |

## User Mapping

The examples use a simple `users` table:

| Column | Kotlin field |
|---|---|
| `id` | `User.id` |
| `first_name` | `User.firstName` |
| `last_name` | `User.lastName` |

`USER_ROW_MAPPER` maps rows into `User`; `tupleMapperOfRecord<User>()` and
`USER_TUPLE_MAPPER` show parameter binding from Kotlin objects.

## Test

```bash
./gradlew :vertx-vertx-sqlclient:test
```

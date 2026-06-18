# Vert.x SQL Client

[English](README.md) | 한국어

이 모듈은 test-driven Vert.x SQL Client 워크샵입니다. H2와 MySQL Testcontainers
runtime 위에서 direct pool query, coroutine transaction helper, SQL template,
Vert.x data-object mapping을 비교합니다.

## 아키텍처

![Vert.x SQL client architecture](../../docs/images/readme-diagrams/vertx-vertx-sqlclient-readme-architecture-01.png)

## Example Areas

| Area | Source | What it demonstrates |
|---|---|---|
| JDBC pool queries | `JDBCPoolExamples` | `Pool.query`, `preparedQuery`, `coAwait()`, `withSuspendTransaction` |
| SQL templates | `SqlClientTemplateExamples` | named parameters, row mappers, tuple mappers, JSON and data-class binding |
| Data objects | `DataObjectMappingExamples` | `UserDataObject`용 generated `@RowMapped` mapper |
| Runtime fixture | `AbstractSqlClientTest` | H2 pool 설정과 reusable MySQL 8 Testcontainers connection options |

## User Mapping

예제는 단순한 `users` table을 사용합니다.

| Column | Kotlin field |
|---|---|
| `id` | `User.id` |
| `first_name` | `User.firstName` |
| `last_name` | `User.lastName` |

`USER_ROW_MAPPER`는 row를 `User`로 매핑합니다. `tupleMapperOfRecord<User>()`와
`USER_TUPLE_MAPPER`는 Kotlin object에서 SQL parameter를 binding하는 방식을
보여줍니다.

## 테스트

```bash
./gradlew :vertx-vertx-sqlclient:test
```

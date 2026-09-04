# R2DBC + WebFlux + Exposed ORM

[한국어](README.ko.md) | English

This module shows a coroutine WebFlux API backed by JetBrains Exposed R2DBC. It
contains two HTTP entrypoint styles over the same `UserService`: an annotation
controller under `/api/users` and a functional router under `/users`.

## Architecture

![R2DBC WebFlux Exposed architecture](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-architecture-01.png)

`UserService` owns the transaction boundary with `suspendTransaction(db = ...)`.
`UserExposedRepository` inherits common CRUD from bluetape4k
`R2dbcRepository<Int, UserRecord>` and implements only user-specific operations.
`UserQueryByExampleRepository` is a separate Spring Data Exposed 2.0.0 repository
factory for coroutine-native Query by Example (QBE) and FluentQuery terminals.
The explicit CRUD repository remains unchanged.

## Request Flow

![R2DBC WebFlux Exposed request sequence](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-sequence-01.png)

The application also seeds sample data on startup. `SchemaInitializer` creates
the Exposed table and inserts four users when the table is empty.

## Schema

![R2DBC WebFlux Exposed users ERD](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-erd-01.png)

The Exposed table is `users`. `login` and `email` are unique, `name` is indexed,
and `avatar` is nullable.

## Query by Example

`GET /api/users/qbe` and `GET /users/qbe` share the same `UserService` query.
`loginPrefix` uses a `STARTING` matcher and `email` uses an exact matcher. The
service applies immutable example snapshots, name sorting, a `UserSummary`
projection (`name`, `login` only), and a `PageRequest`. The response also
contains `count`, `exists`, `page`, `size`, and `hasNext` so callers do not need
to materialize a full result set for metadata.
The implementation uses Spring Data's `ExampleMatcher` and `findBy` FluentQuery
callback directly; `project` defines the selected fields.

```bash
curl 'http://localhost:8080/api/users/qbe?loginPrefix=user&page=0&size=20'
curl 'http://localhost:8080/users/qbe?email=user2%40users.com'
```

QBE terminals own their suspend transaction. A `findAll` result is a cold
`Flow`; collect it inside the transaction scope that owns the R2DBC resource,
and cancel with `take` when bounded consumption is required. This cancellation
boundary is covered by the integration test. `count` and
`exists` are separate SQL terminals and do not require full materialization.

## API Surface

| Style | Method | Path | Handler |
|---|---|---|---|
| Annotation | `GET` | `/api/users` | `UserController.findAll()` |
| Annotation | `GET` | `/api/users/search?email=...` | `UserController.search(...)` |
| Annotation | `GET` | `/api/users/qbe?loginPrefix=...&page=0&size=20` | `UserController.queryByExample(...)` |
| Annotation | `GET` | `/api/users/{id}` | `UserController.findUserById(...)` |
| Annotation | `POST` | `/api/users` | `UserController.addUser(...)` |
| Annotation | `PUT` | `/api/users/{id}` | `UserController.updateUser(...)` |
| Annotation | `DELETE` | `/api/users/{id}` | `UserController.deleteUser(...)` |
| Functional | `GET` | `/users` | `UserHandler.findAll(...)` |
| Functional | `GET` | `/users/search?email=...` | `UserHandler.search(...)` |
| Functional | `GET` | `/users/qbe?loginPrefix=...&page=0&size=20` | `UserHandler.queryByExample(...)` |
| Functional | `GET` | `/users/{id}` | `UserHandler.findUser(...)` |
| Functional | `POST` | `/users` | `UserHandler.addUser(...)` |
| Functional | `PUT` | `/users/{id}` | `UserHandler.updateUser(...)` |
| Functional | `DELETE` | `/users/{id}` | `UserHandler.deleteUser(...)` |

## bluetape4k APIs Used

| API | Where | Why it matters |
|---|---|---|
| `R2dbcRepository<ID, Entity>` | `UserExposedRepository.kt` | Supplies common Exposed R2DBC CRUD operations. |
| `ExposedR2dbcQueryByExampleRepository` | `UserQueryByExampleRepository.kt` | Adds coroutine-native QBE, matcher/sort, FluentQuery projection, page, count, and exists terminals. |
| `ExposedCoroutineFluentQuery` | `UserService.queryByExample(...)` | Keeps the projection and page plan immutable while executing only selected columns. |
| `Runtimex.availableProcessors` | `ExposedR2dbcConfig.kt` | Sizes the R2DBC connection pool from CPU capacity. |
| `asIntOrNull()` | `UserHandler.kt` | Validates functional-route path variables without throwing parser errors. |
| `KLoggingChannel` | Configuration, service, handler, initializer | Keeps coroutine-aware structured logging consistent. |

## Run

```bash
./gradlew :spring-data:r2dbc-webflux-exposed:bootRun
```

## Test

```bash
./gradlew :spring-data:r2dbc-webflux-exposed:test
```

# R2DBC + WebFlux + Exposed ORM

[English](README.md) | 한국어

이 모듈은 JetBrains Exposed R2DBC를 사용하는 coroutine WebFlux API 예제입니다.
같은 `UserService` 위에 두 가지 HTTP 진입점을 제공합니다. `/api/users` 아래의
annotation controller와 `/users` 아래의 functional router입니다.

## 아키텍처

![R2DBC WebFlux Exposed architecture](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-architecture-01.png)

`UserService`는 `suspendTransaction(db = ...)`로 transaction boundary를
관리합니다. `UserExposedRepository`는 bluetape4k
`R2dbcRepository<Int, UserRecord>`에서 공통 CRUD를 상속하고, user 전용 작업만
구현합니다.

## 요청 흐름

![R2DBC WebFlux Exposed request sequence](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-sequence-01.png)

애플리케이션 시작 시 샘플 데이터도 준비합니다. `SchemaInitializer`는 Exposed table을
생성하고, table이 비어 있으면 user 4건을 insert합니다.

## Schema

![R2DBC WebFlux Exposed users ERD](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-erd-01.png)

Exposed table은 `users`입니다. `login`과 `email`은 unique, `name`은 index,
`avatar`는 nullable입니다.

## API Surface

| 방식 | Method | Path | Handler |
|---|---|---|---|
| Annotation | `GET` | `/api/users` | `UserController.findAll()` |
| Annotation | `GET` | `/api/users/search?email=...` | `UserController.search(...)` |
| Annotation | `GET` | `/api/users/{id}` | `UserController.findUserById(...)` |
| Annotation | `POST` | `/api/users` | `UserController.addUser(...)` |
| Annotation | `PUT` | `/api/users/{id}` | `UserController.updateUser(...)` |
| Annotation | `DELETE` | `/api/users/{id}` | `UserController.deleteUser(...)` |
| Functional | `GET` | `/users` | `UserHandler.findAll(...)` |
| Functional | `GET` | `/users/search?email=...` | `UserHandler.search(...)` |
| Functional | `GET` | `/users/{id}` | `UserHandler.findUser(...)` |
| Functional | `POST` | `/users` | `UserHandler.addUser(...)` |
| Functional | `PUT` | `/users/{id}` | `UserHandler.updateUser(...)` |
| Functional | `DELETE` | `/users/{id}` | `UserHandler.deleteUser(...)` |

## 사용한 bluetape4k API

| API | 위치 | 이유 |
|---|---|---|
| `R2dbcRepository<ID, Entity>` | `UserExposedRepository.kt` | Exposed R2DBC 공통 CRUD를 제공합니다. |
| `Runtimex.availableProcessors` | `ExposedR2dbcConfig.kt` | CPU 수를 기준으로 R2DBC connection pool 크기를 정합니다. |
| `asIntOrNull()` | `UserHandler.kt` | Functional route path variable을 parser 예외 없이 검증합니다. |
| `KLoggingChannel` | Configuration, service, handler, initializer | Coroutine-aware structured logging을 일관되게 사용합니다. |

## 실행

```bash
./gradlew :spring-data:r2dbc-webflux-exposed:bootRun
```

## 테스트

```bash
./gradlew :spring-data:r2dbc-webflux-exposed:test
```

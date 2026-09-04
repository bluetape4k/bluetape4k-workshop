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
`UserQueryByExampleRepository`는 coroutine-native Query by Example(QBE)과
FluentQuery terminal을 제공하는 Spring Data Exposed 2.0.0 repository factory
예제입니다. 명시적 CRUD repository는 그대로 유지합니다.

## 요청 흐름

![R2DBC WebFlux Exposed request sequence](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-sequence-01.png)

애플리케이션 시작 시 샘플 데이터도 준비합니다. `SchemaInitializer`는 Exposed table을
생성하고, table이 비어 있으면 user 4건을 insert합니다.

## Schema

![R2DBC WebFlux Exposed users ERD](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-exposed-readme-erd-01.png)

Exposed table은 `users`입니다. `login`과 `email`은 unique, `name`은 index,
`avatar`는 nullable입니다.

## Query by Example

`GET /api/users/qbe`와 `GET /users/qbe`는 같은 `UserService` query를 사용합니다.
`loginPrefix`는 `STARTING` matcher, `email`은 exact matcher로 컴파일합니다. service는
immutable example snapshot, name 정렬, `UserSummary` projection(`name`, `login`만
포함), `PageRequest`를 적용합니다. 응답에는 전체 결과를 materialize하지 않고도
사용할 수 있도록 `count`, `exists`, `page`, `size`, `hasNext`를 함께 반환합니다.
구현은 Spring Data의 `ExampleMatcher`와 `findBy` FluentQuery callback을 직접 사용하며,
`project`로 선택할 field를 선언합니다.

```bash
curl 'http://localhost:8080/api/users/qbe?loginPrefix=user&page=0&size=20'
curl 'http://localhost:8080/users/qbe?email=user2%40users.com'
```

QBE terminal이 suspend transaction을 소유합니다. `findAll` 결과는 cold `Flow`이므로
R2DBC resource를 소유한 transaction scope 안에서 collect하고, bounded consumption이
필요하면 `take`로 취소합니다. 이 취소 경계는 integration test로 검증합니다. `count`와
`exists`는 별도 SQL terminal이며 전체 결과를 materialize하지 않습니다.

## API Surface

| 방식 | Method | Path | Handler |
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

## 사용한 bluetape4k API

| API | 위치 | 이유 |
|---|---|---|
| `R2dbcRepository<ID, Entity>` | `UserExposedRepository.kt` | Exposed R2DBC 공통 CRUD를 제공합니다. |
| `ExposedR2dbcQueryByExampleRepository` | `UserQueryByExampleRepository.kt` | Coroutine-native QBE, matcher/sort, FluentQuery projection, page, count, exists terminal을 추가합니다. |
| `ExposedCoroutineFluentQuery` | `UserService.queryByExample(...)` | Projection과 page plan을 immutable하게 유지하고 선택한 column만 조회합니다. |
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

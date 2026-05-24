# R2DBC + Spring WebFlux (Functional Router)

Spring Data R2DBC with WebFlux functional endpoints (Handler + Router) and Kotlin coroutines.
Uses H2 in-memory database.

## Architecture

```mermaid
sequenceDiagram
    participant C as Client
    participant Router as coRouter<br/>(functional routes)
    participant Handler as UserHandler<br/>(suspend fun)
    participant Svc as UserService<br/>(@Transactional)
    participant Repo as CoroutineCrudRepository
    participant DB as H2 (R2DBC)

    C->>Router: HTTP Request
    Router->>Handler: route to handler method
    activate Handler
    Handler->>Svc: suspend service call
    Svc->>Repo: findAll() / save() / deleteById()
    Repo->>DB: R2DBC SQL
    DB-->>Repo: rows
    Repo-->>Svc: entity / Flow<T>
    Svc-->>Handler: result
    deactivate Handler
    Handler-->>C: ServerResponse
```

## 아키텍처 다이어그램

![r2dbc webflux Architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-diagram-01.png)

![r2dbc webflux Sequence Flow 2 diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-sequence-01.png)

Spring Data R2DBC와 WebFlux 함수형 라우터(Handler + Router)를 조합한 리액티브 CRUD 예제입니다.
H2 인메모리 데이터베이스를 사용합니다.

## Used bluetape4k Features

| Feature | Artifact | Code location | Benefit |
|---------|----------|---------------|---------|
| `KLoggingChannel` | `bluetape4k-logging` | `UserService.kt`, `UserHandler.kt` | Coroutine-aware structured logging |
| `bluetape4k-coroutines` | `bluetape4k-coroutines` | Service layer | Coroutine scope and Flow utilities |
| `bluetape4k-r2dbc` | `bluetape4k-r2dbc` | R2DBC configuration | R2DBC connection helpers and extensions |
| `bluetape4k-assertions` | `bluetape4k-core` | All test classes | `shouldBeEqualTo`, `shouldNotBeNull` readable assertions |

## bluetape4k Before / After

### Coroutine-based service with `@Transactional`

```kotlin
// Before — Reactor Mono/Flux API: verbose chain
@Service
class UserService(private val repository: UserRepository) {
    fun findAll(): Flux<User> = repository.findAll()

    fun addUser(user: UserDTO): Mono<User> =
        repository.save(user.toModel())
            .doOnNext { log.info("Saved user: $it") }
}

// After — bluetape4k KLoggingChannel + coroutine-first style
@Service
@Transactional(readOnly = true)
class UserService(private val repository: UserRepository) {
    companion object : KLoggingChannel()

    fun findAll(): Flow<User> = repository.findAll()

    @Transactional
    suspend fun addUser(user: UserDTO): User? {
        log.debug { "Save new user. ${user.toModel()}" }
        return repository.save(user.toModel())
    }
}
```

### Functional routing with `coRouter`

```kotlin
// Before — annotation-based controller
@RestController
@RequestMapping("/users")
class UserController {
    @GetMapping
    fun findAll() = repository.findAll()
}

// After — WebFlux coRouter (functional endpoint, suspend handlers)
@Bean
fun routes(handler: UserHandler) = coRouter {
    "/users".nest {
        GET("", handler::findAll)
        GET("/{id}", handler::findById)
        POST("", handler::save)
        DELETE("/{id}", handler::delete)
    }
}
```

## 구성 방식

어노테이션 컨트롤러 대신 **함수형 엔드포인트** 방식을 사용합니다:

```kotlin
// Router: 경로 정의
@Bean
fun routes(handler: UserHandler) = coRouter {
    "/users".nest {
        GET("", handler::findAll)
        GET("/{id}", handler::findById)
        POST("", handler::save)
        DELETE("/{id}", handler::delete)
    }
}

// Handler: 요청 처리 (suspend 함수)
suspend fun findAll(request: ServerRequest): ServerResponse =
    ServerResponse.ok().bodyAndAwait(userRepository.findAll())
```

## 어노테이션 컨트롤러 방식과 비교

| 방식 | 특징 |
|---|---|
| 함수형 (이 모듈) | Router + Handler 분리, 명시적 경로 정의 |
| 어노테이션 (`r2dbc-webflux-exposed`) | `@RestController` + `@GetMapping` |

## 참고

- [Spring WebFlux 함수형 엔드포인트](https://docs.spring.io/spring-framework/reference/web/webflux-functional.html)
- [POC WebFlux-R2DBC H2-Kotlin](https://github.com/razvn/webflux-r2dbc-kotlin)

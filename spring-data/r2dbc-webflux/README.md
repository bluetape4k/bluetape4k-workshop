# R2DBC + Spring WebFlux (Functional Router)

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **R2DBC + Spring WebFlux (Functional Router)** as a runnable Spring Data persistence workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Sequence Diagram

![R2DBC + Spring WebFlux (Functional Router) sequence diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-sequence-01.png)

Spring Data R2DBC with WebFlux functional endpoints (Handler + Router) and Kotlin coroutines.
Uses H2 in-memory database.

## Architecture

![R2DBC + Spring WebFlux (Functional Router) Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-readme-architecture-01.png)

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

## Configuration Style

It uses **functional endpoints** instead of annotation-based controllers:

```kotlin
// Router: path definitions
@Bean
fun routes(handler: UserHandler) = coRouter {
    "/users".nest {
        GET("", handler::findAll)
        GET("/{id}", handler::findById)
        POST("", handler::save)
        DELETE("/{id}", handler::delete)
    }
}

// Handler: request handling (suspend function)
suspend fun findAll(request: ServerRequest): ServerResponse =
    ServerResponse.ok().bodyAndAwait(userRepository.findAll())
```

## Comparison with Annotation-Based Controllers

| Style | Characteristics |
|---|---|
| Functional (this module) | Router and Handler separation, explicit path definitions |
| Annotation-based (`r2dbc-webflux-exposed`) | `@RestController` + `@GetMapping` |

## References

- [Spring WebFlux Functional Endpoints](https://docs.spring.io/spring-framework/reference/web/webflux-functional.html)
- [POC WebFlux-R2DBC H2-Kotlin](https://github.com/razvn/webflux-r2dbc-kotlin)

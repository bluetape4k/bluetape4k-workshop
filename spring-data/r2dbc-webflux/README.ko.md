# R2DBC + Spring WebFlux (Functional Router)

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **R2DBC + Spring WebFlux (Functional Router)**를 실행 가능한 Spring Data 영속성 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 시퀀스 다이어그램

![R2DBC + Spring WebFlux (Functional Router) sequence diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-sequence-01.png)

Spring Data R2DBC를 WebFlux functional endpoint(Handler + Router) 및 Kotlin coroutines와 함께 사용합니다.
H2 in-memory database를 사용합니다.

## 아키텍처

![R2DBC + Spring WebFlux (Functional Router) Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-readme-architecture-01.png)

## 사용한 bluetape4k 기능

| 기능 | Artifact | 코드 위치 | 이점 |
|---------|----------|---------------|---------|
| `KLoggingChannel` | `bluetape4k-logging` | `UserService.kt`, `UserHandler.kt` | Coroutine-aware structured logging입니다 |
| `bluetape4k-coroutines` | `bluetape4k-coroutines` | Service layer | Coroutine scope와 Flow utility입니다 |
| `bluetape4k-r2dbc` | `bluetape4k-r2dbc` | R2DBC configuration | R2DBC connection helper와 extension입니다 |
| `bluetape4k-assertions` | `bluetape4k-core` | 모든 test class | `shouldBeEqualTo`, `shouldNotBeNull` 같은 읽기 쉬운 assertion입니다 |

## bluetape4k Before / After

### `@Transactional`을 사용하는 coroutine 기반 service

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

### `coRouter`를 사용하는 functional routing

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

## 설정 스타일

annotation-based controller 대신 **functional endpoint**를 사용합니다.

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

## Annotation-Based Controller와 비교

| 스타일 | 특징 |
|---|---|
| Functional(이 모듈) | Router와 Handler 분리, 명시적인 path definition |
| Annotation-based(`r2dbc-webflux-exposed`) | `@RestController` + `@GetMapping` |

## 참고 자료

- [Spring WebFlux Functional Endpoints](https://docs.spring.io/spring-framework/reference/web/webflux-functional.html)
- [POC WebFlux-R2DBC H2-Kotlin](https://github.com/razvn/webflux-r2dbc-kotlin)

# R2DBC + Spring WebFlux (Functional Router)

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **R2DBC + Spring WebFlux (Functional Router)**를 실행 가능한 Spring Data 영속성 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 흐름 다이어그램

1. `spring-data-r2dbc-webflux`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 자산이 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않은 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

![R2DBC + Spring WebFlux (Functional Router) sequence diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-sequence-01.png)

Spring Data R2DBC를 WebFlux functional endpoint(Handler + Router) 및 Kotlin coroutines와 함께 사용합니다.
H2 in-memory database를 사용합니다.

## 아키텍처

![R2DBC + Spring WebFlux (Functional Router) Graphviz architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-readme-architecture-01.png)

![R2DBC + Spring WebFlux (Functional Router) Diagram 1](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-readme-sequence-01.png)

## 아키텍처 다이어그램

![r2dbc webflux Architecture diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-diagram-01.png)

![r2dbc webflux Sequence Flow 2 diagram](../../docs/images/readme-diagrams/spring-data-r2dbc-webflux-sequence-01.png)

이 reactive CRUD 예제는 Spring Data R2DBC와 WebFlux functional router(Handler + Router)를 결합합니다.
H2 in-memory database를 사용합니다.

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

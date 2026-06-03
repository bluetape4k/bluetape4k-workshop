# Spring Security WebFlux Hello

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Security WebFlux Hello**를 실행 가능한 Spring Security 요청 보호 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 구성, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 시퀀스 다이어그램

![hello-security 시퀀스 다이어그램](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-sequence-01.png)

WebFlux controller, 커스텀 로그인 페이지, in-memory reactive user를 포함한 Reactive Spring Security 예제입니다.

## 아키텍처

![Spring Security WebFlux Hello Graphviz architecture diagram](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-architecture-01.png)

## 이 모듈에서 확인할 내용

- `/`, `/user/index`, `/log-in` WebFlux controller mapping.
- reactive Kotlin DSL로 구성한 `SecurityWebFilterChain`.
- `/`, `/css/**`, `/log-in` 공개 접근.
- `/user/**`의 `ROLE_USER` 보호.
- BCrypt로 encoding된 in-memory user를 사용하는 `MapReactiveUserDetailsService`.

## 실행

```bash
./gradlew :spring-security-webflux-hello-security:bootRun
```

실행 후 `http://localhost:8080/`에 접속하고 다음 계정으로 로그인합니다.

- Username: `user`
- Password: `password`

## 사용하는 bluetape4k 기능

| 모듈 | 기능 | 사용 방식 |
|---|---|---|
| `bluetape4k-logging` | `KLoggingChannel()` | `SecurityConfiguration`의 coroutine-aware structured logging |
| `bluetape4k-coroutines` | Coroutine/Reactor bridge | WebFlux security를 위한 Coroutines + Reactor 통합 |
| `bluetape4k-junit5` | `runSuspendIO { }` | Suspend 기반 integration test runner |

## bluetape4k Before / After

### `KLoggingChannel()` vs plain logger in reactive code

```kotlin
// Before — SLF4J (no coroutine MDC propagation)
private val log = LoggerFactory.getLogger(SecurityConfiguration::class.java)

// After — KLoggingChannel (coroutine context-aware, MDC propagation in WebFlux)
companion object : KLoggingChannel()
log.info { "SecurityWebFilterChain configured" }
```

### Reactive Security DSL — Kotlin invoke extension

```kotlin
// Before — Java-style reactive security builder
http
    .authorizeExchange { spec ->
        spec.pathMatchers("/").permitAll()
            .pathMatchers("/user/**").hasAuthority("ROLE_USER")
    }
    .formLogin { spec -> spec.loginPage("/log-in") }
    .build()

// After — Kotlin DSL via ServerHttpSecurity.invoke
return http {
    authorizeExchange {
        authorize("/log-in", permitAll)
        authorize("/", permitAll)
        authorize("/css/**", permitAll)
        authorize("/user/**", hasAuthority("ROLE_USER"))
    }
    formLogin {
        loginPage = "/log-in"
    }
}
```

## Security Filter Chain (WebFlux)

## 운영 노트

- `MapReactiveUserDetailsService`는 사용자를 메모리에 보관합니다. 운영 환경에서는 database-backed `ReactiveUserDetailsService`로 교체합니다.
- `BCryptPasswordEncoder`는 Spring DI를 통해 `SecurityConfiguration`과 `MapReactiveUserDetailsService`에서 공유됩니다.
- Reactive security는 Reactor thread에서만 실행됩니다. `SecurityWebFilterChain` lambda 안에서는 blocking call을 사용하지 않습니다.

## 소스 맵

- `KotlinWebfluxApplication.kt`는 reactive Spring Boot 애플리케이션을 시작합니다.
- `MainController.kt`는 view route를 매핑합니다.
- `SecurityConfiguration.kt`는 reactive 인가 규칙, form login, password encoding, in-memory user를 정의합니다.
- `application.yml`은 `8080` 포트, AOT, 로컬 개발용 Thymeleaf cache 비활성화를 설정합니다.

# Spring Security WebFlux Hello

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Security WebFlux Hello** as a runnable Spring Security request protection workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `spring-security-webflux-hello-security`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

![hello-security sequence diagram](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-sequence-01.png)

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Reactive Spring Security example with WebFlux controllers, a custom login page, and an in-memory reactive user.

## Architecture

![Spring Security WebFlux Hello Graphviz architecture diagram](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-architecture-01.png)

## What This Module Shows

- WebFlux controller mappings for `/`, `/user/index`, and `/log-in`.
- `SecurityWebFilterChain` configured through the reactive Kotlin DSL.
- Public access for `/`, `/css/**`, and `/log-in`.
- `ROLE_USER` protection for `/user/**`.
- `MapReactiveUserDetailsService` with a BCrypt-encoded in-memory user.

## Running

```bash
./gradlew :spring-security-webflux-hello-security:bootRun
```

Then open `http://localhost:8080/` and sign in with:

- Username: `user`
- Password: `password`

## Used bluetape4k Features

| Module | Feature | Usage |
|---|---|---|
| `bluetape4k-logging` | `KLoggingChannel()` | Coroutine-aware structured logging in `SecurityConfiguration` |
| `bluetape4k-coroutines` | Coroutine/Reactor bridge | Coroutines + Reactor integration for WebFlux security |
| `bluetape4k-junit5` | `runSuspendIO { }` | Suspend-based integration test runner |

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

![Spring Security WebFlux Hello Diagram 1](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-sequence-01.png)

## Operational Notes

- `MapReactiveUserDetailsService` holds users in memory; replace with a database-backed `ReactiveUserDetailsService` for production.
- `BCryptPasswordEncoder` is shared between `SecurityConfiguration` and `MapReactiveUserDetailsService` via Spring DI.
- Reactive security runs entirely on Reactor threads; never use blocking calls inside `SecurityWebFilterChain` lambdas.

## Source Map

- `KotlinWebfluxApplication.kt` starts the reactive Spring Boot application.
- `MainController.kt` maps the view routes.
- `SecurityConfiguration.kt` defines reactive authorization rules, form login, password encoding, and the in-memory user.
- `application.yml` sets port `8080`, enables AOT, and disables Thymeleaf cache for local development.

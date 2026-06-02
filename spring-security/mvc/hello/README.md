# Spring Security MVC Hello

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Security MVC Hello** as a runnable Spring Security request protection workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `spring-security-mvc-hello`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Spring MVC security example with a custom login page, an in-memory user, and role-protected user content.

## Architecture

![Spring Security MVC Hello Graphviz architecture diagram](../../../docs/images/readme-diagrams/spring-security-mvc-hello-readme-architecture-01.png)

![hello Sequence Flow diagram](../../../docs/images/readme-diagrams/spring-security-mvc-hello-diagram-01.png)

## What This Module Shows

- MVC controller mappings for `/`, `/user/index`, and `/log-in`.
- `SecurityFilterChain` configured through the Kotlin DSL.
- Public access for `/`, `/css/**`, and the login page.
- `ROLE_USER` protection for `/user/**`.
- In-memory user credentials encoded with `BCryptPasswordEncoder`.

## Running

```bash
./gradlew :spring-security-mvc-hello:bootRun
```

Then open `http://localhost:8080/` and sign in with:

- Username: `user`
- Password: `password`

## Used bluetape4k Features

| Module | Feature | Usage |
|---|---|---|
| `bluetape4k-logging` | `KLogging()` | Companion-object logger with lazy-lambda messages in `SecurityConfig` |
| `bluetape4k-junit5` | `bluetape4k-junit5` test base | JUnit 5 integration via `AbstractSecurityApplicationTest` |

## bluetape4k Before / After

### `KLogging()` vs plain SLF4J

```kotlin
// Before — SLF4J LoggerFactory
private val log = LoggerFactory.getLogger(SecurityConfig::class.java)
log.info("SecurityConfig initialized")

// After — KLogging() companion object (lazy lambda, zero-cost when disabled)
companion object : KLogging()
log.info { "SecurityConfig initialized" }
```

### Kotlin DSL Security configuration vs Java-style

```kotlin
// Before — Java-style builder chaining
http
    .authorizeHttpRequests { it.requestMatchers("/").permitAll() }
    .formLogin { it.loginPage("/log-in") }
    .build()

// After — Kotlin DSL (Spring Security invoke extension)
http {
    authorizeHttpRequests {
        authorize("/", permitAll)
        authorize("/css/**", permitAll)
        authorize("/user/**", hasAuthority("ROLE_USER"))
    }
    formLogin {
        loginPage = "/log-in"
    }
}
```

## Security Filter Chain

```mermaid
sequenceDiagram
    participant Browser
    participant FC as SecurityFilterChain
    participant AC as AuthenticationManager
    participant Ctrl as MainController

    Browser->>FC: GET /user/index
    FC->>FC: check authorization
    alt not authenticated
        FC-->>Browser: redirect /log-in
        Browser->>FC: POST /log-in (username/password)
        FC->>AC: authenticate (BCrypt)
        AC-->>FC: Authentication
        FC-->>Browser: redirect /user/index
    end
    FC->>Ctrl: authorized request
    Ctrl-->>Browser: 200 user page
```

## Operational Notes

- In-memory user credentials are for demo purposes only; replace with a real `UserDetailsService` in production.
- `BCryptPasswordEncoder` strength defaults to 10; increase to 12+ for production workloads.
- Thymeleaf cache is disabled (`spring.thymeleaf.cache: false`) in `application.yml` for local development only.

## Source Map

- `MainController.kt` maps the MVC pages.
- `SecurityConfig.kt` defines authorization rules, form login, password encoding, and the in-memory user.
- `application.yml` enables AOT and keeps Thymeleaf templates uncached for local development.

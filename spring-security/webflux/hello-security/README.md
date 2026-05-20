# Spring Security WebFlux Hello

[English](README.md) | [한국어](README.ko.md)

Reactive Spring Security example with WebFlux controllers, a custom login page, and an in-memory reactive user.

## Architecture

![Spring Security WebFlux Hello architecture](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-diagram-01.png)

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

## Source Map

- `KotlinWebfluxApplication.kt` starts the reactive Spring Boot application.
- `MainController.kt` maps the view routes.
- `SecurityConfiguration.kt` defines reactive authorization rules, form login, password encoding, and the in-memory user.
- `application.yml` sets port `8080`, enables AOT, and disables Thymeleaf cache for local development.

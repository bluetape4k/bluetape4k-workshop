# Spring Security MVC Hello

[English](README.md) | [한국어](README.ko.md)

Spring MVC security example with a custom login page, an in-memory user, and role-protected user content.

## Architecture

![Spring Security MVC Hello architecture](../../../docs/images/readme-diagrams/spring-security-mvc-hello-diagram-01.png)

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

## Source Map

- `MainController.kt` maps the MVC pages.
- `SecurityConfig.kt` defines authorization rules, form login, password encoding, and the in-memory user.
- `application.yml` enables AOT and keeps Thymeleaf templates uncached for local development.

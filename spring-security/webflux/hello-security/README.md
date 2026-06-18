# Spring Security WebFlux Hello

[한국어](README.ko.md) | English

This module is the reactive WebFlux form-login example. It protects `/user/**`
with `ROLE_USER`, serves a custom login page at `/log-in`, and uses
`MapReactiveUserDetailsService` with a BCrypt-encoded `user/password` account.

## Architecture

![Spring Security WebFlux hello architecture](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-architecture-01.png)

## Security Rules

| Path | Rule | Handler |
|---|---|---|
| `/` | `permitAll` | `MainController.index()` |
| `/css/**` | `permitAll` | static assets |
| `/log-in` | login page | `MainController.login()` |
| `/user/**` | `ROLE_USER` | `MainController.userIndex()` |

## Test Coverage

`MainControllerTest` verifies the behavior with `WebTestClient`:

- `/` returns an unsecured page without authentication.
- `/user/index` redirects anonymous users to `/log-in`.
- `@WithMockUser` can access `/user/index`.

## Run

```bash
./gradlew :spring-security-webflux-hello-security:bootRun
```

Open `http://localhost:8080/` and sign in with `user` / `password`.

## Test

```bash
./gradlew :spring-security-webflux-hello-security:test
```

# Spring Security MVC Hello

[한국어](README.ko.md) | English

This module is the servlet MVC form-login example. It protects `/user/**` with
`ROLE_USER`, serves a custom login page at `/log-in`, and uses an in-memory
`user/password` account encoded with BCrypt.

## Architecture

![Spring Security MVC hello architecture](../../../docs/images/readme-diagrams/spring-security-mvc-hello-readme-architecture-01.png)

## Security Rules

| Path | Rule | View |
|---|---|---|
| `/` | `permitAll` | `index` |
| `/css/**` | `permitAll` | static assets |
| `/log-in` | login page | `login` |
| `/user/**` | `ROLE_USER` | `user/index` |

## Test Coverage

`MainControllerTest` verifies the behavior with MockMvc:

- `/` returns `200 OK` without authentication.
- `/user/index` redirects to `/log-in` when anonymous.
- valid `user/password` form login authenticates.
- invalid credentials remain unauthenticated.
- an authenticated session can access `/user/index`.

## Run

```bash
./gradlew :spring-security:mvc:hello:bootRun
```

Open `http://localhost:8080/` and sign in with `user` / `password`.

## Test

```bash
./gradlew :spring-security:mvc:hello:test
```

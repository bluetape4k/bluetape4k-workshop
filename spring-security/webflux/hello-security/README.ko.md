# Spring Security WebFlux Hello

[English](README.md) | 한국어

이 모듈은 reactive WebFlux form-login 예제입니다. `/user/**`를 `ROLE_USER`로
보호하고, `/log-in`에서 custom login page를 제공하며, BCrypt로 인코딩한
`user/password` 계정을 `MapReactiveUserDetailsService`에 등록합니다.

## 아키텍처

![Spring Security WebFlux hello architecture](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-readme-architecture-01.png)

## Security Rules

| Path | Rule | Handler |
|---|---|---|
| `/` | `permitAll` | `MainController.index()` |
| `/css/**` | `permitAll` | static assets |
| `/log-in` | login page | `MainController.login()` |
| `/user/**` | `ROLE_USER` | `MainController.userIndex()` |

## Test Coverage

`MainControllerTest`는 `WebTestClient`로 다음 동작을 검증합니다.

- `/`는 인증 없이 unsecured page를 반환합니다.
- Anonymous 사용자가 `/user/index`에 접근하면 `/log-in`으로 redirect됩니다.
- `@WithMockUser`는 `/user/index`에 접근할 수 있습니다.

## 실행

```bash
./gradlew :spring-security-webflux-hello-security:bootRun
```

`http://localhost:8080/`을 열고 `user` / `password`로 로그인합니다.

## 테스트

```bash
./gradlew :spring-security-webflux-hello-security:test
```

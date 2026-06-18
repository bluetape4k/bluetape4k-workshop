# Spring Security MVC Hello

[English](README.md) | 한국어

이 모듈은 servlet MVC form-login 예제입니다. `/user/**`를 `ROLE_USER`로 보호하고,
`/log-in`에서 custom login page를 제공하며, BCrypt로 인코딩한 in-memory
`user/password` 계정을 사용합니다.

## 아키텍처

![Spring Security MVC hello architecture](../../../docs/images/readme-diagrams/spring-security-mvc-hello-readme-architecture-01.png)

## Security Rules

| Path | Rule | View |
|---|---|---|
| `/` | `permitAll` | `index` |
| `/css/**` | `permitAll` | static assets |
| `/log-in` | login page | `login` |
| `/user/**` | `ROLE_USER` | `user/index` |

## Test Coverage

`MainControllerTest`는 MockMvc로 다음 동작을 검증합니다.

- `/`는 인증 없이 `200 OK`를 반환합니다.
- Anonymous 사용자가 `/user/index`에 접근하면 `/log-in`으로 redirect됩니다.
- 올바른 `user/password` form login은 인증됩니다.
- 잘못된 credential은 인증되지 않습니다.
- 인증된 session은 `/user/index`에 접근할 수 있습니다.

## 실행

```bash
./gradlew :spring-security:mvc:hello:bootRun
```

`http://localhost:8080/`을 열고 `user` / `password`로 로그인합니다.

## 테스트

```bash
./gradlew :spring-security:mvc:hello:test
```

# Spring Security WebFlux JWT

[English](README.md) | 한국어

이 모듈은 reactive JWT resource-server 예제입니다. HTTP Basic 인증으로
`/token`에서 RSA 서명 토큰을 발급하고, 발급된 토큰을 bearer credential로
사용해 보호된 `GET /` greeting endpoint에 접근합니다.

## 아키텍처

![Spring Security WebFlux JWT architecture](../../../docs/images/readme-diagrams/spring-security-webflux-jwt-readme-architecture-01.png)

## Request Flow

| Request | Authentication | Result |
|---|---|---|
| `POST /token` | HTTP Basic `user/password` | `issuer=self`, `subject=user`, `scope=app`를 가진 RSA 서명 JWT |
| `GET /` | `Authorization: Bearer <token>` | `Hello, user!` |
| unauthenticated request | none | `401 Unauthorized` |

`JwtConfig`는 reactive security filter chain, BCrypt demo user,
`NimbusJwtEncoder`, `NimbusReactiveJwtDecoder`를 구성합니다. Private key는
토큰 서명에 사용하고, public key는 bearer token 검증에 사용합니다.

## 실행

```bash
./gradlew :spring-security-webflux-jwt:bootRun
```

토큰을 요청한 뒤 보호된 endpoint를 호출합니다.

```bash
TOKEN=$(curl -u user:password -X POST http://localhost:8080/token)
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/
```

## 테스트

```bash
./gradlew :spring-security-webflux-jwt:test
```

`HelloControllerTest`는 token 발급, bearer-token 접근, `@WithMockUser` 접근,
unauthenticated request의 `401` 응답을 검증합니다.

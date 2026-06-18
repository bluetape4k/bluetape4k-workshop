# Spring Security WebFlux JWT

[한국어](README.ko.md) | English

This module is the reactive JWT resource-server example. It uses HTTP Basic to
issue an RSA-signed token from `/token`, then accepts that token as a bearer
credential for the protected `GET /` greeting endpoint.

## Architecture

![Spring Security WebFlux JWT architecture](../../../docs/images/readme-diagrams/spring-security-webflux-jwt-readme-architecture-01.png)

## Request Flow

| Request | Authentication | Result |
|---|---|---|
| `POST /token` | HTTP Basic `user/password` | RSA-signed JWT with `issuer=self`, `subject=user`, and `scope=app` |
| `GET /` | `Authorization: Bearer <token>` | `Hello, user!` |
| unauthenticated request | none | `401 Unauthorized` |

`JwtConfig` wires the reactive security filter chain, BCrypt demo user,
`NimbusJwtEncoder`, and `NimbusReactiveJwtDecoder`. The private key signs
tokens; the public key validates bearer tokens.

## Run

```bash
./gradlew :spring-security-webflux-jwt:bootRun
```

Request a token, then call the protected endpoint:

```bash
TOKEN=$(curl -u user:password -X POST http://localhost:8080/token)
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/
```

## Test

```bash
./gradlew :spring-security-webflux-jwt:test
```

`HelloControllerTest` verifies token issuance, bearer-token access,
`@WithMockUser` access, and `401` responses for unauthenticated requests.

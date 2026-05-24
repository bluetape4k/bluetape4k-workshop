# Spring Security WebFlux JWT

[English](README.md) | [한국어](README.ko.md)

Reactive JWT resource server example. It issues RSA-signed JWTs from `/token` and protects the greeting endpoint with bearer-token authentication.

## Architecture

![jwt Sequence Flow diagram](../../../docs/images/readme-diagrams/spring-security-webflux-jwt-diagram-01.png)

## What This Module Shows

- `POST /token` creates a JWT for the authenticated user.
- `GET /` returns `Hello, {user}!` from the authenticated principal.
- RSA public and private keys are loaded from classpath resources.
- WebFlux security combines HTTP Basic token issuance with JWT resource server validation.
- The demo user has username `user`, password `password`, and authority `app`.

## Running

```bash
./gradlew :spring-security-webflux-jwt:bootRun
```

Request a token, then call the protected endpoint:

```bash
TOKEN=$(curl -u user:password -X POST http://localhost:8080/token)
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/
```

## Source Map

- `JwtApplication.kt` starts the reactive Spring Boot application.
- `JwtConfig.kt` defines the `SecurityWebFilterChain`, JWT encoder/decoder, RSA key binding, and demo user.
- `TokenController.kt` signs a 10-hour token with issuer `self`.
- `HelloController.kt` reads the authenticated principal.
- `application.yml` points `jwt.private.key` and `jwt.public.key` at classpath PEM files.

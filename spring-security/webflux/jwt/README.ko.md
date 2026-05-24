# Spring Security WebFlux JWT

[English](README.md) | [한국어](README.ko.md)

Reactive JWT resource server 예제입니다. `/token`에서 RSA 서명 JWT를 발급하고, bearer token 인증으로 greeting endpoint를 보호합니다.

## 아키텍처

![jwt Sequence Flow diagram](../../../docs/images/readme-diagrams/spring-security-webflux-jwt-diagram-01.png)

## 이 모듈에서 확인할 내용

- `POST /token`은 인증된 사용자에 대한 JWT를 생성합니다.
- `GET /`는 인증된 principal에서 `Hello, {user}!`를 반환합니다.
- RSA public/private key는 classpath resource에서 로드됩니다.
- WebFlux security는 HTTP Basic 기반 token 발급과 JWT resource server 검증을 함께 사용합니다.
- 데모 사용자는 username `user`, password `password`, authority `app`를 가집니다.

## 실행

```bash
./gradlew :spring-security-webflux-jwt:bootRun
```

토큰을 요청한 뒤 보호된 endpoint를 호출합니다.

```bash
TOKEN=$(curl -u user:password -X POST http://localhost:8080/token)
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/
```

## 소스 맵

- `JwtApplication.kt`는 reactive Spring Boot 애플리케이션을 시작합니다.
- `JwtConfig.kt`는 `SecurityWebFilterChain`, JWT encoder/decoder, RSA key binding, demo user를 정의합니다.
- `TokenController.kt`는 issuer `self`를 가진 10시간 유효 토큰을 서명합니다.
- `HelloController.kt`는 authenticated principal을 읽습니다.
- `application.yml`은 `jwt.private.key`와 `jwt.public.key`를 classpath PEM 파일로 지정합니다.

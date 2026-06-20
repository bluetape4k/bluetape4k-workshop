# Spring Security Workshop

[English](README.md) | 한국어

이 디렉터리는 세 가지 Spring Security 예제를 묶습니다. Servlet MVC form login,
WebFlux form login, WebFlux JWT resource server security입니다. 이 README는 전체
지도이며, endpoint 수준의 세부 내용은 각 하위 모듈 README에서 확인합니다.

## 아키텍처

![Spring Security workshop architecture](../docs/images/readme-diagrams/spring-security-readme-architecture-01.png)

모든 예제는 테스트와 로컬 실행을 위해 in-memory `user/password` 계정을 사용합니다.
MVC와 WebFlux form-login 모듈은 `/user/**`를 `ROLE_USER`로 보호합니다. JWT 모듈은
OAuth2 resource server JWT 지원으로 모든 exchange를 보호하고 token 발급 인프라를
추가합니다.

## Modules

| Module | Stack | Security focus |
|---|---|---|
| [`mvc/hello`](mvc/hello) | Spring MVC | `SecurityFilterChain`, custom `/log-in`, in-memory user, protected `/user/index`. |
| [`webflux/hello-security`](webflux/hello-security) | Spring WebFlux | `SecurityWebFilterChain`, reactive user details, custom `/log-in`, protected `/user/index`. |
| [`webflux/jwt`](webflux/jwt) | Spring WebFlux + OAuth2 Resource Server | RSA 기반 `JwtEncoder`, `ReactiveJwtDecoder`, bearer-token error handling, authenticated API calls. |

## Common Request Shape

![Spring Security workshop filter sequence](../docs/images/readme-diagrams/spring-security-readme-sequence-01.png)

Servlet과 reactive stack은 서로 다른 filter 구현을 사용하지만, 독자가 이해할 흐름은
같습니다. Public route는 통과하고, protected route는 module에 맞는 인증 방식이
필요하며, 인증되지 않은 요청은 security style에 따라 redirect되거나 reject됩니다.

## 빌드와 테스트

```bash
./gradlew :spring-security:mvc:hello:test
./gradlew :spring-security:webflux:hello-security:test
./gradlew :spring-security:webflux:jwt:test
```

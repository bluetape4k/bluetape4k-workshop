# Spring Security WebFlux Hello

[English](README.md) | [한국어](README.ko.md)

WebFlux 컨트롤러, 커스텀 로그인 페이지, reactive in-memory user를 보여주는 Reactive Spring Security 예제입니다.

## 아키텍처

![hello security Sequence Flow diagram](../../../docs/images/readme-diagrams/spring-security-webflux-hello-security-diagram-01.png)

## 이 모듈에서 확인할 내용

- `/`, `/user/index`, `/log-in` WebFlux 컨트롤러 매핑.
- reactive Kotlin DSL로 구성한 `SecurityWebFilterChain`.
- `/`, `/css/**`, `/log-in` 공개 접근.
- `/user/**`에 대한 `ROLE_USER` 보호.
- BCrypt로 인코딩된 인메모리 사용자를 제공하는 `MapReactiveUserDetailsService`.

## 실행

```bash
./gradlew :spring-security-webflux-hello-security:bootRun
```

실행 후 `http://localhost:8080/`에 접속하고 다음 계정으로 로그인합니다.

- Username: `user`
- Password: `password`

## 소스 맵

- `KotlinWebfluxApplication.kt`는 reactive Spring Boot 애플리케이션을 시작합니다.
- `MainController.kt`는 view route를 매핑합니다.
- `SecurityConfiguration.kt`는 reactive 인가 규칙, form login, password encoding, in-memory user를 정의합니다.
- `application.yml`은 `8080` 포트, AOT, 로컬 개발용 Thymeleaf cache 비활성화를 설정합니다.

# Spring Security MVC Hello

[English](README.md) | [한국어](README.ko.md)

커스텀 로그인 페이지, 인메모리 사용자, role 기반 사용자 페이지 보호를 보여주는 Spring MVC 보안 예제입니다.

## 아키텍처

![hello Sequence Flow diagram](../../../docs/images/readme-diagrams/spring-security-mvc-hello-diagram-01.png)

## 이 모듈에서 확인할 내용

- `/`, `/user/index`, `/log-in` MVC 컨트롤러 매핑.
- Kotlin DSL로 구성한 `SecurityFilterChain`.
- `/`, `/css/**`, 로그인 페이지 공개 접근.
- `/user/**`에 대한 `ROLE_USER` 보호.
- `BCryptPasswordEncoder`로 인코딩되는 인메모리 사용자 계정.

## 실행

```bash
./gradlew :spring-security-mvc-hello:bootRun
```

실행 후 `http://localhost:8080/`에 접속하고 다음 계정으로 로그인합니다.

- Username: `user`
- Password: `password`

## 소스 맵

- `MainController.kt`는 MVC 페이지를 매핑합니다.
- `SecurityConfig.kt`는 인가 규칙, form login, password encoding, in-memory user를 정의합니다.
- `application.yml`은 AOT를 활성화하고 로컬 개발용으로 Thymeleaf template cache를 비활성화합니다.

# Spring Security MVC Hello

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Security MVC Hello** 모듈을 실행 가능한 Spring Security 요청 보호 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 흐름 다이어그램

1. `spring-security-mvc-hello` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

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

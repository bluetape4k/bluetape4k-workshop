# Spring Security MVC Hello

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **Spring Security MVC Hello**를 실행 가능한 Spring Security 요청 보호 워크샵 조각으로 다룹니다. 개발자가 먼저 확인할 경로인 모듈 구성, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 관찰에 초점을 맞춥니다.

## 흐름 다이어그램

1. `spring-security-mvc-hello` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업은 bluetape4k 유틸리티 또는 Spring/Kotlin 통합에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

![hello 시퀀스 다이어그램](../../../docs/images/readme-diagrams/spring-security-mvc-hello-readme-sequence-01.png)

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 이 모듈에 전용 시퀀스 자산이 있으면 아래 이미지가 상호작용 순서를 보여주며, 그렇지 않으면 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

커스텀 로그인 페이지, 인메모리 사용자, role로 보호되는 사용자 콘텐츠를 포함한 Spring MVC 보안 예제입니다.

## 아키텍처

![Spring Security MVC Hello Graphviz architecture diagram](../../../docs/images/readme-diagrams/spring-security-mvc-hello-readme-architecture-01.png)

## 이 모듈에서 확인할 내용

- `/`, `/user/index`, `/log-in` MVC controller mapping.
- Kotlin DSL로 구성한 `SecurityFilterChain`.
- `/`, `/css/**`, login page 공개 접근.
- `/user/**`의 `ROLE_USER` 보호.
- `BCryptPasswordEncoder`로 encoding된 in-memory user credential.

## 실행

```bash
./gradlew :spring-security-mvc-hello:bootRun
```

실행 후 `http://localhost:8080/`에 접속하고 다음 계정으로 로그인합니다.

- Username: `user`
- Password: `password`

## 사용하는 bluetape4k 기능

| 모듈 | 기능 | 사용 방식 |
|---|---|---|
| `bluetape4k-logging` | `KLogging()` | `SecurityConfig`의 lazy-lambda 메시지를 사용하는 companion-object logger |
| `bluetape4k-junit5` | `bluetape4k-junit5` test base | `AbstractSecurityApplicationTest`를 통한 JUnit 5 통합 |

## bluetape4k Before / After

### `KLogging()` vs plain SLF4J

```kotlin
// Before — SLF4J LoggerFactory
private val log = LoggerFactory.getLogger(SecurityConfig::class.java)
log.info("SecurityConfig initialized")

// After — KLogging() companion object (lazy lambda, zero-cost when disabled)
companion object : KLogging()
log.info { "SecurityConfig initialized" }
```

### Kotlin DSL Security configuration vs Java-style

```kotlin
// Before — Java-style builder chaining
http
    .authorizeHttpRequests { it.requestMatchers("/").permitAll() }
    .formLogin { it.loginPage("/log-in") }
    .build()

// After — Kotlin DSL (Spring Security invoke extension)
http {
    authorizeHttpRequests {
        authorize("/", permitAll)
        authorize("/css/**", permitAll)
        authorize("/user/**", hasAuthority("ROLE_USER"))
    }
    formLogin {
        loginPage = "/log-in"
    }
}
```

## Security Filter Chain

![Spring Security MVC Hello Diagram 1](../../../docs/images/readme-diagrams/spring-security-mvc-hello-readme-sequence-01.png)

## 운영 노트

- In-memory user credential은 데모 용도입니다. 운영 환경에서는 실제 `UserDetailsService`로 교체합니다.
- `BCryptPasswordEncoder` strength 기본값은 10입니다. 운영 워크로드에서는 12 이상으로 높입니다.
- `application.yml`에서 Thymeleaf cache가 비활성화되어 있습니다(`spring.thymeleaf.cache: false`). 이는 로컬 개발 전용 설정입니다.

## 소스 맵

- `MainController.kt`는 MVC page를 매핑합니다.
- `SecurityConfig.kt`는 인가 규칙, form login, password encoding, in-memory user를 정의합니다.
- `application.yml`은 AOT를 활성화하고 로컬 개발용으로 Thymeleaf template cache를 비활성화합니다.

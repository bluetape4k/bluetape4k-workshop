# 2026-05-24 Spring Boot Advanced — BT Feature Documentation (Issue #84)

## 작업 개요

6개 대상 모듈 README(영문)에 bluetape4k(BT) 기능 표 + Before/After 예제 + Mermaid 다이어그램을 추가했다.

대상 모듈:
- `spring-boot/resilience4j-coroutines` — 이미 완비된 영문 README; 변경 없음
- `gateway/api-gateway` — 전면 재작성: `KLoggingChannel`, `bluetape4k-bucket4j`, Mermaid rate-limit flow
- `spring-security/mvc/hello` — BT 기능 표, Kotlin DSL Before/After, Security Filter Chain Mermaid, 운영 주의사항
- `spring-security/webflux/hello-security` — `KLoggingChannel`, reactive DSL Before/After, WebFlux filter chain Mermaid
- `spring-security/webflux/jwt` — `KLoggingChannel`, OAuth2 resource server DSL Before/After, JWT token flow Mermaid
- `spring-modulith/events-deep-dive` — `KLogging`, event listener Before/After, event publication + module boundary Mermaid
- `spring-modulith/jpa-demo` — 전면 재작성: 4-module architecture, `KLogging`/`bluetape4k-hibernate`/`bluetape4k-idgenerators`, event flow Mermaid
- `spring-boot/cache-caffeine` — 영문 Overview 섹션 추가 (BT 기능 표는 기존에 완비)
- `spring-boot/cache-redis` — 영문 Overview 섹션 추가 (BT 기능 표는 기존에 완비)

## 적용된 주요 패턴

### 1. `KLoggingChannel` vs `KLogging` — 선택 기준

`KLoggingChannel`은 Kotlin coroutine context (MDC 포함)를 전파하므로 WebFlux·코루틴 컴포넌트에 사용한다.
`KLogging`은 일반 동기 코드(Spring MVC, JPA 서비스 등)에 사용한다.
두 경우 모두 lazy-lambda 형식(`log.info { "..." }`)으로 작성해야 비활성화 시 문자열 생성 비용을 제거한다.

### 2. Kotlin DSL for Spring Security

Spring Security의 MVC DSL(`HttpSecurity.invoke { }`)과
WebFlux DSL(`ServerHttpSecurity.invoke { }`)은
Java-style `.requestMatchers().and().formLogin()` 체이닝보다 가독성이 높다.
`@EnableWebSecurity` / `@EnableWebFluxSecurity`와 함께 Kotlin DSL을 쓸 때는
`import org.springframework.security.config.annotation.web.builders.invoke` (MVC) 또는
`import org.springframework.security.config.web.server.invoke` (WebFlux)를 추가해야 한다.

### 3. JWT 운영 보안 주의사항

- RSA 개인키를 소스에 커밋하지 말 것 — 개발 환경에서만 `classpath:certs/app.key` 허용.
- 토큰 TTL은 10시간으로 설정돼 있으나 프로덕션에서는 15~60분으로 단축하고 refresh token을 구현해야 한다.
- `BearerTokenServerAuthenticationEntryPoint`는 RFC 6750 규격 `WWW-Authenticate` 헤더를 자동 반환한다.

### 4. Spring Modulith 모듈 경계 강제

`ApplicationModules.of(SpringModulith::class.java).verify()`는
내부 타입을 다른 모듈에서 직접 주입할 경우 테스트에서 즉시 실패한다.
모듈 간 의존은 반드시 `*ExternalAPI` 인터페이스를 통해 한다.

### 5. `@TransactionalEventListener` vs `@EventListener`

`@EventListener`는 현재 트랜잭션 안에서 실행되어 리스너 예외가 원본 TX를 롤백할 수 있다.
`@TransactionalEventListener`는 커밋 후 실행되므로 보조 작업(알림, 캐시 업데이트 등)에 적합하다.
Spring Modulith의 `@ApplicationModuleListener`는 `@TransactionalEventListener`를 모듈 경계와 함께 조합한다.

### 6. Bucket4j Redis rate-limiter (API Gateway)

`bluetape4k-bucket4j`의 빌더 DSL은 `LettuceBasedProxyManager` / `RedissonBasedProxyManager` 위에
토큰 버킷을 선언적으로 구성한다. 응답 헤더 `X-RateLimit-Remaining`을 필터에서 삽입해
클라이언트가 잔여 토큰 수를 알 수 있도록 한다.

## 파일 변경 목록

| 파일 | 변경 내용 |
|---|---|
| `gateway/api-gateway/README.md` | 전면 재작성 — Mermaid 아키텍처 + rate-limit flow |
| `spring-security/mvc/hello/README.md` | BT 표 + Before/After + Security filter chain Mermaid |
| `spring-security/webflux/hello-security/README.md` | BT 표 + Before/After + WebFlux filter chain Mermaid |
| `spring-security/webflux/jwt/README.md` | BT 표 + Before/After + JWT token flow Mermaid |
| `spring-modulith/events-deep-dive/README.md` | BT 표 + Before/After + event publication + module boundary Mermaid |
| `spring-modulith/jpa-demo/README.md` | 전면 재작성 — 4-module Mermaid + BT 표 + event flow Mermaid |
| `spring-boot/cache-caffeine/README.md` | 영문 Overview 섹션 추가 |
| `spring-boot/cache-redis/README.md` | 영문 Overview 섹션 추가 |

## 향후 가이드

- `spring-modulith-events-exposed` 구현 시 `spring-modulith-events-jpa` 의존을 대체 가능 — Issue #25.
- `gateway/api-gateway`의 `GatewayConfig.kt`가 현재 빈 클래스; 실제 route DSL 구현 시 README의 YAML 예시와 일치시킬 것.
- WebFlux 보안 컴포넌트에서 blocking call(`Thread.sleep`, JDBC 등)을 절대 사용하지 말 것 — `withContext(Dispatchers.IO)`로 wrapping.

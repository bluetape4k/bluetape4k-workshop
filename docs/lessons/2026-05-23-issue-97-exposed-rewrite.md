# Lesson: Issue #97 — Exposed Examples Rewrite

**Date**: 2026-05-23  
**Branch**: `feat/issue-97-exposed-rewrite`  
**Scope**: `exposed/mvc-jdbc`, `exposed/mvc-virtualthread`, `exposed/webflux-r2dbc`

---

## 목적

5개의 구형 `exposed/` 모듈을 3개의 실전형 앱으로 전면 재작성.
각 앱은 다른 트랜잭션 전략을 사용하는 동일한 Author/Book/Product/Order 도메인을 구현.

---

## 발생한 이슈 및 해결

### 1. `spring.jackson.serialization.write-dates-as-timestamps` 컨텍스트 로딩 실패

**증상**: 모든 테스트가 `initializationError` 로 실패.  
**원인**: Jackson 3 (`tools.jackson.databind.SerializationFeature`) 는 Spring Boot 의
lenient kebab-case enum 변환이 `write-dates-as-timestamps` → `WRITE_DATES_AS_TIMESTAMPS` 를
처리하지 못함.  
**해결**: `application.yml` 에서 `spring.jackson` 섹션 전체 제거.  
**교훈**: Spring Boot 4 + Jackson 3 에서는 `spring.jackson.serialization.*` 속성을
사용하지 않거나 프로그래매틱으로 `ObjectMapper` 빈을 구성한다.

### 2. `WebTestClient` not auto-configured in `@SpringBootTest(RANDOM_PORT)`

**증상**: `NoSuchBeanDefinitionException: WebTestClient`.  
**원인**: Spring Boot 4의 `@SpringBootTest(RANDOM_PORT)` 는 Spring MVC, WebFlux 모두에서
`WebTestClient` 를 자동 등록하지 않음 (`@WebFluxTest` 슬라이스에서만 자동 등록).  
**해결**: 모든 abstract test base에서 `@Autowired WebTestClient` → `@LocalServerPort port` +
`lazy { WebTestClient.bindToServer().baseUrl("http://localhost:$port").build() }` 로 교체.  
**패턴**:
```kotlin
@LocalServerPort
protected val port: Int = 0

protected val webTestClient: WebTestClient by lazy {
    WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
}
```

### 3. `virtualFuture{}.get()` → `ExecutionException` 래핑 문제

**증상**: `mvc-virtualthread` 에서 nonexistent 리소스 요청 시 404 대신 500 반환.  
**원인**: `virtualFuture(executor) { ... }.get()` 는 내부 예외를 `java.util.concurrent.ExecutionException`
으로 래핑. Spring 7.0.7의 `@RestControllerAdvice` 는 `ExecutionException.cause` 를 자동으로
언래핑하지 않아 `@ExceptionHandler(NoSuchElementException::class)` 에 매핑되지 않고
generic 500 handler 로 fallthrough.  
**해결**: `GlobalExceptionHandler` 에 `ExecutionException`/`CompletionException` 전용 핸들러 추가:
```kotlin
@ExceptionHandler(ExecutionException::class, CompletionException::class)
fun handleWrapped(e: Exception): ResponseEntity<ErrorResponse> {
    return when (val cause = e.cause ?: e) {
        is NoSuchElementException -> handleNotFound(cause)
        is InsufficientStockException -> handleInsufficientStock(cause)
        is IllegalArgumentException -> handleBadRequest(cause)
        else -> handleGeneral(cause as Exception)
    }
}
```
**교훈**: `virtualFuture{}.get()` 를 사용하는 모든 MVC 모듈은 `GlobalExceptionHandler` 에
반드시 이 unwrapping 핸들러가 있어야 한다.

### 4. Exposed v1 import 경로

- `import org.jetbrains.exposed.v1.core.eq` — 최상위 연산자 (WHERE clause)
- `import org.jetbrains.exposed.v1.core.minus` — 컬럼 산술 (stock 차감)
- `import org.jetbrains.exposed.v1.jdbc.selectAll` 등 — JDBC DML
- ❌ `SqlExpressionBuilder.eq` 는 deprecated error path

### 5. `bluetape4k-virtualthread-jdk25` vs `jdk21` 충돌

**원인**: JVM 21 테스트 환경에서 `jdk25` 런타임 사용 불가.  
**해결**: `runtimeOnly(libs.bluetape4k.virtualthread.jdk21)` 로 교체.

### 6. R2DBC open-cursor 문제

**증상**: AuthorService.delete 에서 같은 커넥션으로 Flow 스트리밍 중 DELETE 시도 → 런타임 에러.  
**해결**: `val books = bookRepo.findByAuthorId(id).toList()` — DELETE 전에 Flow 를 먼저 collect.

---

## 검증 결과

| 모듈 | 테스트 수 | 결과 |
|------|----------|------|
| `exposed-mvc-jdbc` | 11 | ✅ 0 failures |
| `exposed-mvc-virtualthread` | 11 | ✅ 0 failures |
| `exposed-webflux-r2dbc` | 11 | ✅ 0 failures |

---

## 미래 작업 지침

1. Spring Boot 4 + Jackson 3 에서 `spring.jackson.*` 속성을 쓰려면 먼저 Jackson 3 enum 호환성 확인.
2. `@SpringBootTest(RANDOM_PORT)` 테스트는 항상 `WebTestClient.bindToServer()` 패턴 사용.
3. VT 모듈의 `GlobalExceptionHandler` 에는 반드시 `ExecutionException`/`CompletionException` unwrapping 핸들러 포함.
4. `virtualFuture` 사용 서비스의 예외 전파는 항상 통합 테스트로 404/409 시나리오 검증.

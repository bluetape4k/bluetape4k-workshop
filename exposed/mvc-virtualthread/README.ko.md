# exposed/mvc-virtualthread

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **exposed/mvc-virtualthread**를 실행 가능한 Exposed 데이터 접근 워크숍 조각으로 다룹니다. 개발자가 가장 먼저 확인하는 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄여 주는 라이브러리와 프레임워크 API 관찰에 초점을 둡니다.

## 흐름 다이어그램

1. `exposed-mvc-virtualthread`에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 작업을 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, 메트릭, 트레이스 또는 테스트 기대값으로 보이는 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크숍 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여 주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

Spring MVC + Virtual Threads + Exposed JDBC 조합이며 **`@Transactional`을 사용하지 않습니다**.

## 아키텍처

![exposed/mvc-virtualthread Graphviz architecture diagram](../../docs/images/readme-diagrams/exposed-mvc-virtualthread-readme-architecture-01.png)

![Exposed MVC Virtual Thread Architecture](../../docs/images/readme-diagrams/exposed-mvc-virtualthread-architecture-01.png)

## 사용한 bluetape4k 기능

| 기능 | Artifact | 코드 위치 | 이점 |
|---------|----------|---------------|---------|
| `KLogging` | `bluetape4k-logging` | 모든 서비스 클래스 | 지연 lambda logging |
| `virtualFuture(executor) { }` | `bluetape4k-virtualthread-api` | `AuthorService.kt`, `OrderService.kt` | 블로킹 JDBC 작업을 VT executor에 제출 — coroutine/reactor 불필요 |
| `ShutdownQueue.register(executor)` | `bluetape4k-virtualthread-api` | `TomcatConfig.kt` | 직접 lifecycle을 관리하지 않아도 VT executor를 graceful shutdown |
| `bluetape4k-virtualthread-jdk21` | `bluetape4k-virtualthread-jdk21` | runtime classpath | JDK 21 virtual thread provider |
| `Fakers.faker` | `bluetape4k-junit5` | 테스트 base class | 결정적 fake data 생성 |
| `shouldBeEqualTo` matchers | `bluetape4k-assertions` | 모든 테스트 클래스 | 읽기 쉬운 Kotlin idiomatic assertion |
| `PostgreSQLServer.Launcher.postgres` | `bluetape4k-testcontainers` | `AbstractMvcVirtualthreadTest` | Singleton Testcontainers PostgreSQL — `@Testcontainers` boilerplate 없음 |

## bluetape4k 적용 전 / 적용 후

### Virtual Thread DB 실행

```kotlin
// Before — @Transactional with potential pinning risk under virtual threads
@Service
class AuthorService(private val repo: AuthorRepository) {
    @Transactional
    fun save(dto: AuthorDTO): AuthorDTO {
        return repo.insert(dto)      // synchronized monitor → pins the carrier thread
    }
}

// After — bluetape4k virtualFuture: explicit VT submission, no @Transactional
@Service
class AuthorService(
    private val repo: AuthorRepository,
    private val executor: ExecutorService,
) {
    fun save(dto: AuthorDTO): AuthorDTO =
        virtualFuture(executor) {
            transaction(db) { repo.insert(dto) }
        }.get()
}
```

### Executor lifecycle 관리

```kotlin
// Before — manual PreDestroy or ApplicationListener
@Bean
fun virtualThreadExecutor(): ExecutorService {
    val exec = Executors.newVirtualThreadPerTaskExecutor()
    // Remember to shut it down somewhere...
    return exec
}

// After — bluetape4k ShutdownQueue: zero-boilerplate graceful shutdown
@Bean
fun virtualThreadExecutor(): ExecutorService {
    val exec = Executors.newVirtualThreadPerTaskExecutor()
    ShutdownQueue.register(exec)   // automatically called on JVM shutdown
    return exec
}
```

## 핵심 패턴

- **VT Executor bean**: `TomcatConfig`의 `@Bean fun virtualThreadExecutor(): ExecutorService` + `TomcatProtocolHandlerCustomizer`.
- **TX pattern**: `virtualFuture(executor) { transaction(db) { ... } }.get()` — `@Transactional` 아님.
- **Exception unwrapping**: `GlobalExceptionHandler`가 `Future.get()`에서 감싼 `ExecutionException`/`CompletionException`을 처리합니다.
- **`@Transactional` 없음**: `rg "@Transactional" src/main/` 결과 0건으로 확인합니다.

## 실행

```bash
./gradlew :exposed-mvc-virtualthread:bootRun
# http://localhost:8081/swagger-ui/index.html
```

## 테스트

```bash
./gradlew :exposed-mvc-virtualthread:test
```

| 테스트 클래스 | 범위 |
|-----------|---------|
| `AuthorControllerTest` | Author + Book CRUD |
| `ProductControllerTest` | Product CRUD |
| `OrderControllerTest` | Order place, cancel, 404/409 cases |
| `PlaceOrderRollbackTest` | Rollback on stock failure |
| `ConcurrentPlaceOrderTest` | N=10 VT threads, stock=1 → 1 success, 9 conflicts (409) |

# Ktor REST 코루틴 워크숍 모듈 - 디자인 사양

- **날짜:** 2026-05-25
- **모듈 경로:** `bluetape4k-workshop/ktor/rest-coroutines`
- **상태:** 승인됨 — 구현 계획 준비 완료(2-R단계 CONVERGENCE ✅)
- **저자:** bluetape4k-workshop 기여자
- **대상:** 워크숍 모듈을 구현하는 엔지니어 + Bluetape4k 라이브러리 관리자(간격 입력)

---

## 1. 요약/목표

Bluetape4k 라이브러리를 기본 툴킷으로 사용하여 Kotlin 코루틴으로 구축된 **Ktor 3.4.3 REST 서버**를 보여주는 그린필드 워크숍 모듈을 추가합니다. 이 모듈은 인 메모리 저장소, `kotlinx-serialization`을 통한 JSON 콘텐츠 협상, SSE 스트리밍 엔드포인트, **bluetape4k-jackson3**이 지원하는 NDJSON 내보내기 엔드포인트를 갖춘 작은 **책 카탈로그** 서비스입니다.

구체적인 목표:

- 이 작업공간에 표준 Ktor + 코루틴 REST 모양을 표시합니다.
- 주요 행복한 경로인 `KLoggingChannel`, `runSuspendTest`, `bluetape4k-assertions`, `bluetape4k-jackson3`에서 Bluetape4k API를 사용하세요.
- Bluetape4k Jackson 3과 Ktor `kotlinx-serialization`이 충돌 없이 하나의 모듈에 공존할 수 있는지 확인합니다.
- 구체적인 기능 격차를 드러냅니다. 현재는 **`bluetape4k-ktor`** 통합 라이브러리가 없습니다.

골이 아닌 경우:

- 데이터베이스 / Exposed / R2DBC 통합이 없습니다. 저장소는 메모리에 있습니다.
- 보안 없음(인증, CORS 강화) - 워크샵 범위에만 해당됩니다.
- No Koin / Spring DI — 명시적인 생성자 연결.
- 잭슨 2는 어디에도 없습니다. 이 모듈은 HTTP 페이로드에 대해 `kotlinx-serialization` 위에 있는 Jackson-3 전용입니다.

---

## 2. 설계 위험

| # | 위험 | 가능성 | 영향 | 완화 |
|---|------|------------|--------|------------|
| R1 | **Jackson 버전 분할**: `io.ktor:ktor-serialization-jackson`은 Jackson 2(`com.fasterxml.jackson.*`)이지만 `bluetape4k-jackson3`는 Jackson 3(`tools.jackson.*`)입니다. 클래스 경로에서 이들을 혼합하면 혼란과 `ObjectMapper`의 우발적인 이중 바인딩 위험이 있습니다. | 높음 | 중간 | **`ktor-serialization-jackson`에 의존하지 마세요.** ContentNegotiation에는 `kotlinx-serialization-json`을 사용하세요. 씬 `Jackson3Support` 도우미 뒤에 있는 전용 `/books/export` NDJSON 경로에서만 `bluetape4k-jackson3`을 사용하세요. |
| R2 | **SSE Netty 엔진**: Ktor SSE 플러그인은 엔진을 인식합니다. 오용(예: `send {}` 내부 동기 차단)은 스트리밍을 중단하고 Netty 이벤트 루프를 중단시킵니다. | 중간 | 높음 | SSE 생산자를 `suspend` 호출로 제한합니다. `Flow<Book>` 및 `collect { send(...) }`을 통해 방출합니다. 이를 경로 파일에 문서화하십시오. `ktor-client`를 통해 스트림을 소비하는 스모크 테스트를 추가합니다. |
| R3 | **`bluetape4k-ktor` 모듈이 존재하지 않습니다**: 각 작업장 모듈은 배선을 재창조합니다. 모듈 간의 드리프트가 발생할 가능성이 있습니다(로깅, 오류 매핑, JSON 구성). | 높음 | 중간 | 나중에 `bluetape4k-ktor` 라이브러리의 시드가 될 수 있도록 워크숍 모듈의 배선을 최소화하고 관용적으로 유지하세요. §9에 공백을 기록하고 워크숍 README에서 링크를 연결하세요. |
| R4 | **Kotlin 직렬화 및 `data class` 직렬화 가능 계약**: bluetape4k 규칙에서는 `serialVersionUID`을 사용하여 `java.io.Serializable`를 구현하려면 `data class`이 필요합니다. kotlinx의 `@Serializable`는 관련이 없으므로 혼동해서는 안 됩니다. | 중간 | 낮음 | 도메인 `Book`은 명시적인 `serialVersionUID`을 사용하여 `@Serializable`(kotlinx)와 `java.io.Serializable`을 모두 구현합니다. `domain/Book.kt`에 기록되어 있습니다. |
| R5 | **상태 매핑 누출**: `StatusPages`이 없는 경로에서 도메인 예외를 발생시키면 500이 생성되고 스택 추적이 누출됩니다. | 중간 | 중간 | `ApplicationModule`에 `StatusPages`을 설치하고 `DomainError.NotFound` -> 404, `DomainError.Conflict` -> 409, `IllegalArgumentException` -> 400을 매핑합니다. |
| R6 | **`embeddedServer`의 결함 테스트**: 단위 테스트에서 임의 포트에서 Netty를 회전시키면 I/O 및 타이밍 위험이 추가됩니다. | 낮음 | 중간 | 모든 테스트에 `ktor-server-test-host`(인메모리)의 `testApplication { }`을 사용하세요. 테스트에는 실제 Netty 서버가 없습니다. |

---

## 3. 접근 방식 비교 - JSON 직렬화 전략

Ktor의 HTTP 본문 직렬화를 위해 세 가지 신뢰할 수 있는 옵션이 고려되었습니다.

### 옵션 A — `kotlinx-serialization-json` (CHOSEN)

- **장점:** Kotlin에 기본입니다. `@Serializable`을 통해 컴파일 시 확인된 스키마. 반사가 없습니다. 이 작업공간의 `libs.versions.toml`에 이미 있습니다. Jackson 2와 Jackson 3의 분할을 깔끔하게 방지합니다. 다중 플랫폼 친화적입니다. Ktor 3.4.3 `ContentNegotiation`와 잘 작동합니다.
- **단점:** 주석 프로세서 / KSP 플러그인을 적용해야 합니다. 모듈별 `kotlin.serialization` 플러그인이 필요합니다. Jackson과 약간 다른 규칙(예: 기본값, 다형성)
- **맞춤:** 현대적인 Kotlin + Ktor을 보여주는 워크숍에 가장 적합합니다. R1를 완전히 피합니다.

### 옵션 B — `bluetape4k-jackson3` 위에 손으로 굴린 `ContentConverter`

- **장점:** Bluetape4k가 선호하는 `ObjectMapper`을 재사용합니다. 코드베이스 전체에 걸쳐 단일 JSON 엔진. Ktor의 콘텐츠 협상 확장성을 보여줍니다.
- **단점:** 중요하지 않음: Ktor 3의 API에 대해 `ContentConverter.serializeNullable` / `deserialize`을 구현해야 하며 문자 집합, 스트리밍 및 `TypeInfo`을 처리해야 합니다. 엣지 케이스가 잘못되기 쉽습니다. 실제로 미래의 `bluetape4k-ktor` 라이브러리에 존재해야 하는 유지 관리 부채를 추가합니다.
- **맞춤:** 올바른 아이디어, 잘못된 레이어. 워크숍 모듈이 아닌 `bluetape4k-ktor`에 속합니다.

### 옵션 C — `ktor-serialization-jackson` (잭슨 2)

- **장점:** 공식적으로 지원됩니다. 친숙한 잭슨 DSL.
- **단점:** **Jackson 2**(`com.fasterxml.jackson.*`)를 Bluetape4k의 **Jackson 3**(`tools.jackson.*`) 옆 클래스 경로로 드래그합니다. 호환되지 않는 두 개의 Jackson 제품군이 공존하는 것은 클래스 경로 냄새이며 프로젝트의 Jackson-3 전용 방향을 위반합니다. 향후 `bluetape4k-jackson3` 모듈은 이 패턴에 의존할 수 없습니다.
- **적합:** 거부됨. 작업 공간의 Jackson 3 입장과 직접적으로 충돌합니다.

**결정: 옵션 A.** 옵션 B는 `bluetape4k-ktor`(§9)에 대한 향후 작업으로 기록됩니다. 이 모듈에서는 옵션 C가 명시적으로 금지됩니다.

---

## 4. 아키텍처 결정(고정)

| ID | 결정 | 근거 |
|----|----------|-----------|
| AD-1 | **엔진 = Netty** `embeddedServer(Netty, ...)`를 통해. | 기본 Ktor 엔진; 대부분의 프로덕션 배포에 적합하며 SSE에 대해 잘 문서화되어 있습니다. |
| AD-2 | **ContentNegotiation = `kotlinx-serialization-json`**. | §3 옵션 A를 참조하세요. Jackson 2/3 분할을 방지합니다. |
| AD-3 | **Jackson 3의 범위는 한 곳으로 제한됩니다**: `/books/export` NDJSON via `bluetape4k-jackson3` `ObjectMapper`. | Bluetape4k Jackson 3을 ContentNegotiation에 유출하지 않고 시연합니다. |
| AD-4 | **DI = `fun Application.module()` 내부의 명시적인 생성자 연결**. | 코인 없음/봄. 워크숍 범위를 좁게 유지하고 코드 경로를 검사 가능하게 유지합니다. |
| AD-5 | **인메모리 `BookRepository`** 인터페이스 뒤. | DB 통합은 범위를 벗어납니다. 인터페이스는 향후 R2DBC/Exposed 교체를 위해 문을 열어 둡니다. |
| AD-6 | **`KLoggingChannel` 서비스, 저장소, 경로 및 테스트 기본 클래스의 모든 `companion object`**에 있습니다. | 코루틴 친화적인 로깅을 위한 작업공간 표준입니다. |
| AD-7 | **`StatusPages`** `DomainError`을 HTTP 상태 코드로 매핑합니다. AND에는 포괄적인 `exception<Throwable>` 핸들러가 있습니다. | 포괄적인 기능이 없으면 매핑되지 않은 예외(Ktor 자체 `BadRequestException`, 역직렬화 실패, 포착되지 않은 `NullPointerException`)는 스택 추적을 포함할 수 있는 Ktor의 기본 500 핸들러에 도달합니다. 순서: 특정 하위 클래스가 먼저, `Throwable` 마지막. |
| AD-8 | **경로 테스트에서는 `@Test fun foo() = testApplication { application { module(...) } }`을 사용합니다. 순수 service/repo 단위 테스트는 `bluetape4k-junit5`의 `runSuspendTest { }`**를 사용합니다. | `testApplication`은 `TestResult`를 반환하고 NOT는 정지 함수입니다. `runSuspendTest { }` 안에 래핑하는 것은 유형 오류입니다. 두 하네스는 서로 다른 범위를 제공합니다. `testApplication` 통합의 경우 HTTP; Ktor 스택이 없는 순수 코루틴 로직의 경우 `runSuspendTest`. |
| AD-9 | **도메인 유형은 `data class`이고 `serialVersionUID`로 `java.io.Serializable`**를 구현합니다. | 작업공간 `CLAUDE.md`은 모든 `data class`에 대해 직렬화 가능을 요구합니다. |
| AD-10 | **프로덕션 코드에는 `runBlocking`이 없습니다.** 경로 전체에서 기능을 일시 중지합니다. | 작업공간 코루틴 정책. |

---

## 5. 부품 설계

패키지 루트: `io.bluetape4k.workshop.ktor`

### 5.1 `Main.kt`

- **책임:** 프로세스 진입점.
- **모양:** `fun main()`이 `embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true)`을 호출합니다.
- **참고:** 비즈니스 논리가 없습니다. `module()`은 독립적으로 테스트 가능합니다.

### 5.2 `ApplicationModule.kt`

- **책임:** Ktor `Application.module()` 확장. **주입 가능한 협력자를 허용해야 합니다**. 그래야 테스트가 공개 API를 거치지 않고 미리 시드된 저장소를 주입할 수 있습니다.
- **서명:** `fun Application.module(repository: BookRepository = InMemoryBookRepository(), jackson3: Jackson3Support = Jackson3Support())`. `Main.kt`은 기본값으로 호출합니다. 테스트는 명시적 인스턴스를 통과합니다.
- **공유 Json 인스턴스:** 파일 상단에 정의 — `internal val AppJson = Json { ignoreUnknownKeys = true; prettyPrint = false }`. `internal`(`private` 아님)을 표시하면 `BookRoutes.kt`이 `import io.bluetape4k.workshop.ktor.AppJson`로 가져올 수 있습니다. `install(ContentNegotiation) { json(AppJson) }` 및 SSE 인코딩 블록 모두에서 `AppJson`를 참조하세요.
- **배선 순서:**
  1. `CallLogging`을 설치합니다(상태 + 방법 + URI; `KLoggingChannel` 호환 SLF4J 사용).
  2. `json(AppJson)`를 사용하여 `ContentNegotiation`를 설치합니다.
  3. `SSE`를 설치합니다.
  4. `StatusPages` 설치:
     - `exception<BadRequestException> { call, e -> call.respond(400, mapOf("error" to (e.message ?: "Bad request"))) }`
     - `exception<DomainError.NotFound> { call, e -> call.respond(404, mapOf("error" to e.message, "type" to "NotFound")) }`
     - `exception<DomainError.Conflict> { call, e -> call.respond(409, mapOf("error" to e.message, "type" to "Conflict")) }`
     - `exception<IllegalArgumentException> { call, e -> call.respond(400, mapOf("error" to (e.message ?: "Bad request"), "type" to "BadRequest")) }`
     - **포괄(필수):** `exception<Throwable> { call, e -> log.error(e) { "Unhandled exception" }; call.respond(500, mapOf("error" to "Internal server error", "type" to "Internal")) }`
  5. `service = BookService(repository)`를 구성합니다.
  6. 경로 등록: `routing { healthRoutes(); bookRoutes(service, jackson3) }`.
- **컴패니언 객체:** `KLoggingChannel` 모듈 수준 부트스트랩 메시지용 로거.

### 5.3 `domain/Book.kt`

- `@Serializable data class Book(val id: String, val title: String, val author: String, val year: Int) : java.io.Serializable`
  - `companion object { private const val serialVersionUID: Long = 1L }`
- 순수한 가치 객체. DB 주석이 없습니다.

### 5.4 `domain/DomainError.kt`

- `sealed class DomainError(message: String) : RuntimeException(message)`
  - `class NotFound(id: String) : DomainError("Book not found: id=$id")`
  - `class Conflict(message: String) : DomainError(message)`
- `StatusPages`은 각 하위 클래스를 특정 상태에 매핑합니다.

### 5.5 `repository/BookRepository.kt`

```kotlin
interface BookRepository {
    /**
     * Returns all books currently in the catalog.
     *
     * ## Behavior / Contract
     * - Returns an empty list when no books exist.
     * - Does not include books created after this call starts.
     */
    suspend fun findAll(): List<Book>

    /**
     * Returns a book by id, or null if not found.
     */
    suspend fun findById(id: String): Book?

    /**
     * Persists a new book and makes it available for subsequent reads and streams.
     *
     * ## Behavior / Contract
     * - If a book with the same id already exists, throws [DomainError.Conflict].
     * - Subsequent [findAll] and [findById] calls will include this book.
     * - Emits to [stream] consumers immediately after persistence.
     *
     * @throws DomainError.Conflict if id already exists
     * @throws IllegalArgumentException if any field fails validation (see [BookService.create])
     */
    suspend fun save(book: Book): Book

    /**
     * Returns a [Flow] backed by a hot [MutableSharedFlow] for SSE live streaming.
     *
     * ## Behavior / Contract
     * - **Hot source:** events are emitted by the shared flow whether or not any collector is active.
     *   Only books saved *after* a collector subscribes are delivered to that collector.
     * - **Must not emit via blocking calls.** Emissions occur on the SSE route's coroutine context
     *   (Netty event-loop or virtual thread). Any blocking I/O inside the flow will starve the engine.
     * - Callers that need blocking I/O inside a collector must wrap in `withContext(Dispatchers.IO)`.
     * - The Flow is live-only: only books saved *after* collection starts are emitted.
     * - Each new `collect` receives its own independent subscription from the shared source.
     */
    fun stream(): Flow<Book>
}
```

### 5.6 `repository/InMemoryBookRepository.kt`

- 백업 저장소: `ConcurrentHashMap<String, Book>`.
- 스트림 소스: `MutableSharedFlow<Book>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)`.
  - **이유:** `SUSPEND`는 소비자가 느릴 때 생산자(POST 핸들러)에 역압을 가하여 배송을 보장합니다. `DROP_OLDEST`은 이벤트를 자동으로 삭제합니다.
  - **지속 완화(필수):** 느리거나 정지된 SSE 구독자는 결국 `save()` 내에서 `sharedFlow.emit()`을 일시 중지하여 POST 핸들러 코루틴을 차단합니다. `InMemoryBookRepository.save`에서 시간 초과로 대기를 제한합니다.
    ```kotlin
    val emitted = withTimeoutOrNull(5_000) { sharedFlow.emit(book) } != null
    if (!emitted) log.warn { "SSE emit timed out for book ${book.id}; book saved, event dropped" }
    ```
    이는 POST 대기 시간을 제한하면서 내구성 보장(책이 지도에 관계없이 기록됨)을 유지합니다. 테스트 환경에 적합한 제한 시간을 선택합니다(인메모리 테스트에는 5초가 안전합니다. 프로덕션에서는 조정하세요).
- `save`은 지도에 쓴 후 공유 흐름으로 내보냅니다.
- 저렴하더라도 모든 메소드는 `suspend`이므로 호출자가 경계를 착각할 수 없습니다.
- `companion object : KLoggingChannel()`.

### 5.7 `service/BookService.kt`

- 유효성 검사 + 저장소 호출을 조정합니다.
- **필드 유효성 검사 상수**(`companion object` 상수로 정의):
  - `MAX_ID_LENGTH = 128`
  - `MAX_TITLE_LENGTH = 500`
  - `MAX_AUTHOR_LENGTH = 200`
  - `YEAR_RANGE = 1..3000`
- 검증에서는 입력 매개변수에 Bluetape4k `requireXxx()` 확장을 사용합니다.
  - `book.id.requireNotBlank("id")`, `requireMaxLength(book.id, MAX_ID_LENGTH, "id")`
  - `book.title.requireNotBlank("title")`, `requireMaxLength(book.title, MAX_TITLE_LENGTH, "title")`
  - `book.author.requireNotBlank("author")`, `requireMaxLength(book.author, MAX_AUTHOR_LENGTH, "author")`
  - `book.year in YEAR_RANGE` 또는 이에 상응하는 것
- 중복 ID 조건을 `DomainError.Conflict`으로 변환합니다.
- `companion object : KLoggingChannel()`.

### 5.8 `routes/HealthRoutes.kt`

- `fun Route.healthRoutes()`:
  - `get("/health") { call.respondText("OK") }`.

### 5.9 `routes/BookRoutes.kt`

- `fun Route.bookRoutes(service: BookService, jackson3: Jackson3Support)`:
  - `GET /books` -> `call.respond(service.list())`.
  - `GET /books/{id}` -> `service.get(id) ?: throw DomainError.NotFound(id)`.
  - `POST /books` -> `call.receive<Book>()`를 통해 `Book`을 수신하고, `service.create(...)`을 호출하고, 201 + 본문으로 응답합니다.
  - `GET /books/stream` -> `sse("/books/stream") { service.stream().collect { book -> send(ServerSentEvent(data = AppJson.encodeToString(book))) } }`를 통해 SSE을 엽니다.
    - **필요한 경로:** `sse("/books/stream") { ... }`, NOT bare `sse { ... }`를 사용하세요. 베어 폼은 일치하지 않는 GET 경로를 섀도잉하는 현재 `Route` 범위(예: 애플리케이션 루트 `/`)에 마운트됩니다. Ktor 3 `Route.sse(path, handler)` 대 `Route.sse(handler)` 과부하당.
    - **`CancellationException` 계약(필수):** `send(...)` 관련 오류 처리는 광범위한 예외를 포착하기 전에 `CancellationException`를 다시 발생시켜야 합니다.
      ```kotlin
      try {
          send(ServerSentEvent(data = AppJson.encodeToString(book)))
      } catch (e: CancellationException) {
          throw e  // client disconnected — let coroutine cancel normally
      } catch (e: Exception) {
          log.warn(e) { "SSE send failed for book ${book.id}" }
      }
      ```
    - SSE JSON는 `Json` 컴패니언 기본값이 아닌 **`AppJson`**(`ApplicationModule.kt`에서 `import io.bluetape4k.workshop.ktor.AppJson`로 가져옴)를 사용합니다. 이렇게 하면 ContentNegotiation 및 SSE 인코딩이 동일한 구성을 공유하게 됩니다.
  - `GET /books/export` -> `call.respondBytesWriter(contentType = ContentType("application", "x-ndjson"))`을 사용하여 `ByteWriteChannel`를 얻은 다음 `jackson3.writeNdjson(this, books)`을 호출합니다.
    ```kotlin
    val books = service.list()
    call.respondBytesWriter(contentType = ContentType("application", "x-ndjson")) {
        jackson3.writeNdjson(this, books)
    }
    ```
- `companion object : KLoggingChannel()`.

### 5.10 `json/Jackson3Support.kt`

- Bluetape4k Jackson 3 `ObjectMapper` 주위의 얇은 포장지.
- `suspend fun writeNdjson(out: ByteWriteChannel, books: Iterable<Book>)`을 노출합니다.
- **디스패처 사용법:** `ByteWriteChannel.writeStringUtf8`은 코루틴 기반 일시 중지 함수입니다. NOT는 `withContext(Dispatchers.IO)`로 래핑되어야 합니다. CPU바운드 직렬화 단계(`objectMapper.writeValueAsString(book)`)에서만 대규모 페이로드에 `withContext(Dispatchers.Default)`를 사용할 수 있습니다. 올바른 패턴:
  ```kotlin
  suspend fun writeNdjson(out: ByteWriteChannel, books: Iterable<Book>) {
      for (book in books) {
          val json = objectMapper.writeValueAsString(book)  // CPU, fast in practice
          out.writeStringUtf8(json + "\n")                  // suspend, non-blocking
      }
  }
  ```
- `companion object : KLoggingChannel()`.

### 5.11 파일 맵

```
ktor/rest-coroutines/
├── build.gradle.kts
├── src/main/kotlin/io/bluetape4k/workshop/ktor/
│   ├── Main.kt
│   ├── ApplicationModule.kt
│   ├── domain/
│   │   ├── Book.kt
│   │   └── DomainError.kt
│   ├── repository/
│   │   ├── BookRepository.kt
│   │   └── InMemoryBookRepository.kt
│   ├── service/
│   │   └── BookService.kt
│   ├── routes/
│   │   ├── HealthRoutes.kt
│   │   └── BookRoutes.kt
│   └── json/
│       └── Jackson3Support.kt
├── src/main/resources/
│   └── logback.xml
├── src/test/kotlin/io/bluetape4k/workshop/ktor/
│   ├── AbstractKtorTest.kt
│   ├── routes/BookRoutesTest.kt
│   ├── routes/HealthRoutesTest.kt
│   └── routes/BookStreamTest.kt
└── src/test/resources/
    ├── junit-platform.properties   # required: copy from templates/test/resources/
    └── logback-test.xml            # required: copy from templates/test/resources/
```

---

## 6. 데이터 Flow

### 6.1 읽기 흐름(`GET /books/{id}`)

```
HTTP request
  -> Ktor router (BookRoutes)
  -> BookService.get(id)
  -> BookRepository.findById(id)
  -> ConcurrentHashMap lookup
  -> null? -> throw DomainError.NotFound
            -> StatusPages -> 404 + JSON error body
  -> Book   -> ContentNegotiation (kotlinx-serialization) -> 200 + JSON
```

### 6.2 쓰기 흐름(`POST /books`)

```
HTTP request body (JSON)
  -> ContentNegotiation deserializes to Book
  -> BookRoutes
  -> BookService.create(book)
       require(book.title) etc.
       repository.findById(book.id) != null -> DomainError.Conflict
  -> InMemoryBookRepository.save
       map[book.id] = book
       sharedFlow.emit(book)        // feeds /books/stream
  -> respond 201 Created + JSON body
```

### 6.3 SSE 흐름(`GET /books/stream`)

```
Client opens SSE connection
  -> Ktor SSE plugin negotiates text/event-stream
  -> BookRoutes opens sse("/books/stream") { } session
  -> service.stream() returns repository.stream() : Flow<Book>
  -> collect { book ->
       try {
         send(ServerSentEvent(data = AppJson.encodeToString(book)))
       } catch (e: CancellationException) {
         throw e  // client disconnected — propagates coroutine cancellation
       } catch (e: Exception) {
         log.warn(e) { "SSE send failed" }  // log, continue if transient
       }
     }
  -> Connection stays open; new POSTs emit to sharedFlow (SUSPEND strategy) -> piped to client
  -> Client disconnect cancels SSE coroutine -> CancellationException propagates -> Flow collector removed
```

### 6.4 NDJSON 내보내기 흐름(`GET /books/export`)

```
Client requests /books/export
  -> BookRoutes calls service.list() (materializes current books)
  -> call.respondBytesWriter(contentType = ContentType("application","x-ndjson")) { channel ->
       Jackson3Support.writeNdjson(channel, books)
         for (book in books) {
           val json = objectMapper.writeValueAsString(book)   // CPU, in-place (no IO dispatch)
           channel.writeStringUtf8(json + "\n")               // suspend write to response
         }
     }
  -> Response body is a newline-delimited JSON stream, flushed per record
```

---

## 7. API 계약서

별도의 언급이 없는 한 모든 본문은 `application/json`입니다. 오류는 `{"error": "<message>", "type": "<DomainError class simple name>"}`로 반환됩니다.

### 7.1 `GET /health`

- **200 OK**, `text/plain`, 본문 `OK`.

### 7.2 `GET /books`

- **200 OK**, 본문:
  ```json
  [
    {"id": "b-1", "title": "Kotlin in Action", "author": "Dmitry Jemerov", "year": 2017}
  ]
  ```

### 7.3 `GET /books/{id}`

- **200 OK** `Book` 본문 포함, 또는
- **404 찾을 수 없음**, 본문 `{"error": "Book not found: id=b-99", "type": "NotFound"}`.

### 7.4 `POST /books`

- 요청 본문: `Book` JSON. `id`은(는) 필수이며 고유해야 합니다.
- **201 생성됨** `Book` 본문 또는
- **400 잘못된 요청** 유효성 검사 실패(`IllegalArgumentException` -> 400) 또는
- **409 충돌** `id`이(가) 이미 존재하는 경우(`DomainError.Conflict` -> 409).

**필드 유효성 검사 규칙:**

| 필드 | 유형 | 제약 |
|-------|------|-------------|
| `id` | 문자열 | 공백이 아닌 1-128자 |
| `title` | 문자열 | 공백이 아닌 1~500자 |
| `author` | 문자열 | 공백이 아닌 1~200자 |
| `year` | 정수 | 범위 1..3000 |

모든 검증 실패는 400 상태의 `{"error": "<message>", "type": "BadRequest"}`을 생성합니다.

**오류 응답 형식(모든 엔드포인트):**

```json
{"error": "<human-readable message>", "type": "<error category>"}
```

| 예외 | HTTP 상태 | `type` 값 |
|-----------|-------------|-------------|
| `DomainError.NotFound` | 404 | `"NotFound"` |
| `DomainError.Conflict` | 409 | `"Conflict"` |
| `IllegalArgumentException` | 400 | `"BadRequest"` |
| Ktor `BadRequestException` (기형적인 신체) | 400 | `"BadRequest"` |
| 기타 `Throwable` | 500 | `"Internal"` (세부정보 노출 없음) |

참고: `type` 필드는 워크숍 범위에 허용되는 내부 클래스 이름을 노출합니다. 프로덕션에서는 불투명한 오류 코드로 대체합니다.

### 7.5 `GET /books/stream`

- **200 OK**, `Content-Type: text/event-stream`.
- `data: {"id":"b-1","title":"...","author":"...","year":2017}\n\n` 형태의 이벤트 스트림.
- 연결은 계속 열려 있습니다. `POST /books`을 통해 게시된 새 책이 새 이벤트로 도착합니다.

### 7.6 `GET /books/export`

- **200 OK**, `Content-Type: application/x-ndjson`.
- 본문은 **bluetape4k-jackson3** `ObjectMapper`을 통해 작성된 한 줄에 하나의 JSON 객체입니다.
  ```
  {"id":"b-1","title":"Kotlin in Action","author":"Dmitry Jemerov","year":2017}
  {"id":"b-2","title":"Programming Kotlin","author":"Venkat Subramaniam","year":2019}
  ```

---

## 8. 사용된 Bluetape4k 기능(Bluetape4k-첫 번째 요구 사항 표)

| 우려사항 | Bluetape4k API 사용됨 | 적용되는 곳 | 메모 |
|---------|---------------------|---------------|-------|
| 로깅 | `io.bluetape4k.logging.coroutines.KLoggingChannel` | `ApplicationModule`, `BookService`, `InMemoryBookRepository`, `BookRoutes`, `HealthRoutes`, `Jackson3Support`, `AbstractKtorTest`의 `companion object` | 코루틴 인식 로거; 작업 공간 표준에 따라 의무화됩니다. |
| 입력 유효성 검사 | `requireNotBlank`, `requireGreaterThan`(또는 이와 동등한 것) | `BookService.create` | 손으로 굴린 `require(...)` 블록을 대체합니다. |
| 코루틴 테스트 | `bluetape4k-junit5`의 `runSuspendTest { }` | `routes/*Test.kt`의 모든 일시 중지 테스트 | 테스트에서는 `runBlocking`을 피합니다. |
| 검증문 | `bluetape4k-assertions` (`shouldBeEqualTo`, `shouldNotBeNull`, `shouldContain` 등) | 모든 테스트 어설션 | 작업 공간 표준; JUnit `assertEquals`이 없습니다. |
| JSON(대체 경로) | `bluetape4k-jackson3` `ObjectMapper` | `/books/export`에 대한 `Jackson3Support.writeNdjson` | Ktor ContentNegotiation와 충돌하지 않고 Jackson 3 사용법을 보여줍니다. |
| 기타 유틸리티 | `bluetape4k-core` 확장(`String.requireNotBlank` 등) | 서비스/도메인 | 기준. |

이 모듈에서는 금지됨: Jackson 2(`com.fasterxml.jackson.*`), `ktor-serialization-jackson`, Koin, Spring DI, `runBlocking` 프로덕션 코드 `@Synchronized`.

---

## 9. Bluetape4k 기능 누락(간격)

**간격:** 오늘은 `bluetape4k-ktor` 라이브러리가 없습니다.

구체적으로 다음 도우미는 워크샵 모듈에 인라인되어야 했으며 재사용 가능한 라이브러리로 더 좋습니다.

1. `bluetape4k-jackson3` `ObjectMapper` 대신 `ContentConverter` 어댑터를 사용하므로 Jackson 3을 선호하는 프로젝트에서는 이를 Ktor `ContentNegotiation`에 직접 연결할 수 있습니다. (§3 옵션 B 참조 - 워크숍 수준에서는 거부되지만 도서관 수준에서는 유효합니다.)
2. `ObjectMapper`에 대해 매개변수화된 NDJSON 스트리밍 도우미(`Route.respondNdjson(Iterable<T>)`).
3. Bluetape4k 도메인 예외에 대한 정식 `StatusPages` 매퍼(예: 서비스 간에 공유되는 기본 `DomainError` 봉인 클래스)
4. Ktor 및 Bluetape4k 로그 채널의 일관성을 유지하기 위한 `KLoggingChannel` 지원 `CallLogging` 포맷터입니다.
5. SSE 편의: `Route.sseFlow(flow: Flow<T>, encode: (T) -> String)` 경로 수준 상용구를 제거합니다.

**권장되는 후속 조치:** `bluetape4k-vertx`과 범위가 유사한 `bluetape4k-projects/bluetape4k-ktor`를 생성합니다. 이 워크샵 모듈은 `json/Jackson3Support.kt` 및 `ApplicationModule.kt`이 시드 구현 역할을 할 수 있도록 의도적으로 작성되었습니다.

추적: 이 사양을 참조하는 `bluetape4k-projects` 아래에 문제를 제출하세요.

---

## 10. Gradle / 통합 구축

### 10.0 종속성 선언 정책

**Workshop CLAUDE.md**에는 "버전 관리: `buildSrc/src/main/kotlin/Libs.kt` — 새 의존성은 여기에 먼저 정의되어 있습니다."라고 명시되어 있습니다.

이 모듈은 다음과 같은 이유로 새 좌표(Ktor)를 `libs.versions.toml`에 직접 추가합니다. 워크샵은 정식 정보 소스로서 Gradle 버전 카탈로그(`libs.versions.toml`)로 마이그레이션하고 있습니다. 이는 vertx, kafka 및 redis 모듈이 이미 선언된 방식과 일치합니다.

**결정:** Ktor 좌표는 `libs.versions.toml`에 들어갑니다. `Libs.kt`이 나중에 Ktor 별칭을 계속 참조하는 경우 중복 항목을 제거하세요. 이 결정은 CLAUDE.md 규칙과의 차이를 문서화하는 작업공간 정책 요구 사항을 충족하기 위해 **여기에 명시적으로 기록**됩니다.

### 10.1 `settings.gradle.kts` (bluetape4k-워크샵)

적절한 위치에 추가하십시오.

```kotlin
includeModules("ktor", false, true)
```

그러면 `:ktor-rest-coroutines`이 자동 등록됩니다(플랫 프로젝트 이름 — `includeModules`과 `withProjectName=false, withBaseDir=true`가 `basePath + "-" + dirName` 생성). 확인: `./gradlew projects | grep ktor-rest-coroutines`.

### 10.2 `gradle/libs.versions.toml` (bluetape4k-워크숍)

```toml
[versions]
ktor = "3.4.3"

[libraries]
ktor-bom                          = { module = "io.ktor:ktor-bom", version.ref = "ktor" }
ktor-server-core                  = { module = "io.ktor:ktor-server-core" }
ktor-server-netty                 = { module = "io.ktor:ktor-server-netty" }
ktor-server-content-negotiation   = { module = "io.ktor:ktor-server-content-negotiation" }
ktor-server-call-logging          = { module = "io.ktor:ktor-server-call-logging" }
ktor-server-status-pages          = { module = "io.ktor:ktor-server-status-pages" }
ktor-server-sse                   = { module = "io.ktor:ktor-server-sse" }
ktor-server-test-host             = { module = "io.ktor:ktor-server-test-host" }
ktor-serialization-kotlinx-json   = { module = "io.ktor:ktor-serialization-kotlinx-json" }
ktor-client-core                  = { module = "io.ktor:ktor-client-core" }
ktor-client-content-negotiation   = { module = "io.ktor:ktor-client-content-negotiation" }
```

### 10.3 `ktor/rest-coroutines/build.gradle.kts`

필수 조각:

```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(platform(libs.ktor.bom))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(Libs.bluetape4k_core)
    implementation(Libs.bluetape4k_jackson3)
    implementation(Libs.bluetape4k_logging)
    implementation(Libs.bluetape4k_coroutines)

    testImplementation(Libs.bluetape4k_junit5)
    testImplementation(Libs.bluetape4k_assertions)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.content.negotiation)
}
```

루트 빌드에서 상속된 표준 작업공간 JVM 툴체인(`Java 25`, ZGC 플래그)을 적용합니다.

---

## 11. 테스트 전략

- **프레임워크:** JUnit 5 + MockK + bluetape4k-assertions + `bluetape4k-junit5`.
- **서버 하니스:** Ktor의 `testApplication { application { module(repository = ...) } }`. 실제 Netty가 없습니다.
- **하네스 분할(필수):**
  - 경로/통합 테스트: `@Test fun testFoo() = testApplication { application { module(repository = preseeded) }; ... }`. NOT가 `runSuspendTest`에 싸여 있습니다.
  - 순수 서비스/저장소 테스트: `@Test fun testBar() = runSuspendTest { ... }`. `testApplication`이 아닙니다.
- **보장 대상:**
  - `BookRoutesTest`: list, get-existing, get-missing -> 404, create -> 201, create-duplicate -> 409, 유효성 검사 -> 400(`testApplication` 앞에 픽스쳐 데이터가 있는 사전 시드 `InMemoryBookRepository`).
  - `HealthRoutesTest`: `/health`은 `OK`를 반환합니다.
  - `BookStreamTest`: 경쟁 조건을 피하기 위해 백그라운드 구독 + 이벤트 컬렉션으로 구성됩니다. 구체적인 패턴:
    ```kotlin
    @Test
    fun testStreamReceivesEvent() = testApplication {
        val preseeded = InMemoryBookRepository()
        application { module(repository = preseeded) }

        val sseClient = createClient { install(SSE) }
        val events = Channel<String>(Channel.BUFFERED)

        // Start subscription BEFORE the POST.
        // testApplication block receiver is ApplicationTestBuilder (NOT CoroutineScope and NOT TestScope).
        // Neither bare launch {} nor backgroundScope.launch {} is accessible.
        // Create a child scope from the current suspend context explicitly:
        val subscriptionScope = CoroutineScope(coroutineContext + SupervisorJob())
        val subscription = subscriptionScope.launch {
            sseClient.sse("/books/stream") {
                incoming.collect { event ->
                    event.data?.let { events.send(it) }
                }
            }
        }

        // Allow subscription to establish (in-memory; 100 ms is generous)
        delay(100)

        // POST a new book
        val response = client.post("/books") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"test-1","title":"T","author":"A","year":2024}""")
        }
        response.status shouldBeEqualTo HttpStatusCode.Created

        // Assert event arrives within 5 s
        val received = withTimeoutOrNull(5_000) { events.receive() }
        received.shouldNotBeNull()
        received shouldContain "test-1"

        subscription.cancel()
        subscriptionScope.cancel()
        events.close()
    }
    ```
    주요 불변성: (1) 구독이 `CoroutineScope(coroutineContext + SupervisorJob()).launch {}`을 사용하여 *전에* POST 시작되었습니다 — `testApplication` 블록 수신자는 `ApplicationTestBuilder`입니다(`CoroutineScope`도 아니고 `TestScope`도 아님). 따라서 `launch {}`도 `backgroundScope.launch {}`도 컴파일되지 않습니다. (2) `delay(100)`은 구독자에게 핫 `SharedFlow`에 등록할 시간을 제공합니다(`coroutineContext`는 일시 중지 블록 내에서 사용할 수 있으므로 테스트 디스패처에서는 지연이 가상입니다). (3) 인메모리 하네스에는 5초의 타임아웃이 안전합니다. (4) `subscriptionScope.cancel()` + `events.close()`은 어설션 후 정리됩니다.
  - `BookServiceTest`: `FakeBookRepository`을 사용한 순수 정지 단위 테스트입니다.
  - `InMemoryBookRepositoryTest`: `runSuspendTest`을 통한 순수 정지 단위 테스트.
  - `Jackson3SupportTest` (선택 사항): NDJSON `bluetape4k-jackson3` `ObjectMapper` 왕복.
- **테스트 기본 클래스 `AbstractKtorTest`:** `companion object : KLoggingChannel()`을 보유합니다.
- **테스트에서는 실제 포트 바인딩이 없습니다.**

---

## 12. 운영 참고사항

- 기본 포트: 8080(나중에 env를 통해 재정의 가능 - 여기서는 범위를 벗어남)
- `logback.xml`: 최소 패턴 기반 구성; INFO에 하나의 콘솔 어펜더; `io.ktor`를 INFO로 내립니다.
- Docker / Testcontainers가 필요하지 않습니다. 모듈은 순수 JVM입니다.

**생산 격차(의도적인 워크숍 범위 누락 - 생산 배포 전 문서):**

| 갭 | 워크숍 입장 | 생산 요구 사항 |
|-----|-----------------|------------------------|
| **정상적인 종료 없음** | `embeddedServer(...).start(wait = true)`은 SIGTERM에서 즉시 종료됩니다. 활성 SSE 클라이언트의 연결이 갑자기 끊어집니다. 진행 중인 POST에서 SSE 이벤트가 손실될 수 있습니다. | `server.stop(gracePeriodMillis=1000, timeoutMillis=5000)`을 호출하는 JVM 종료 후크를 추가하고 `Application.module()`에 `ApplicationStopping` 리스너를 등록합니다. |
| **요청 본문 크기 제한 없음** | `call.receive<Book>()`은 유효성 검사 전에 전체 본문을 읽습니다. 다중 MB 페이로드는 필드 수준 제약 조건이 실행되기 전에 OOM를 발생시킵니다. | Netty `maxRequestSize`를 구성하거나 대형 본체를 조기에 거부하는 Ktor `RequestValidation` 플러그인을 설치하세요. |
| **SSE 정지 위험** | SSE 버퍼가 채워질 때 `withTimeoutOrNull(5_000)` 경계 POST 대기 시간. 시간 초과 후에는 이벤트가 기록되고 삭제됩니다. | 역방향 프록시 또는 애플리케이션 수준에서 연결별 `SharedFlow.emit` 시간 제한 + 동시 SSE 연결(예: 세마포어)에 대한 하드 캡을 고려하세요. |
| **SSE 연결 유지 하트비트 없음** | 유휴 기간 동안 자동 연결; proxies/LBs는 30~60초 후에 유휴 TCP 연결을 얻을 수 있습니다. | `sse { }` 블록 내에 `while(true) { delay(15.seconds); send(ServerSentEvent(comment = "ping")) }` 코루틴을 추가하거나, 가능한 경우 Ktor 3.4+ 내장 `heartbeatPeriod`을 사용하세요. |
| **SSE 연결 캡 없음** | 제한되지 않은 동시 SSE 연결은 Netty 작업자 스레드와 FD 제한을 소모합니다. | 애플리케이션 수준 `AtomicInteger` 카운터 또는 `Semaphore`을 추가합니다. 정의된 최대값을 초과하는 경로 수준에서 연결을 거부합니다. |

---

## 13. 승인 기준/완료의 정의

다음 사항이 **모두** 충족되면 모듈이 완료된 것으로 간주됩니다.

### 13.1 빌드 및 레이아웃

- [ ] `settings.gradle.kts`은 `includeModules("ktor", false, true)`를 통해 `:ktor-rest-coroutines`(플랫 이름)을 등록합니다.
- [ ] `gradle/libs.versions.toml`에는 Ktor 3.4.3 BOM + §10.2에 나열된 라이브러리가 포함되어 있습니다.
- [ ] `ktor/rest-coroutines/build.gradle.kts`은 `kotlin.serialization` 플러그인과 §10.3에 나열된 종속성만 적용합니다.
- [ ] 모듈은 작업공간의 표준 컴파일러 플래그를 사용하여 Java 25에서 컴파일됩니다.

### 13.2 코드

- [ ] §5.11의 모든 파일은 §5에 설명된 책임과 함께 존재합니다.
- [ ] `com.fasterxml.jackson.*`를 가져오는 파일이 없습니다. (잭슨 2는 결석합니다.)
- [ ] `io.ktor.serialization.jackson.*`를 가져오는 파일이 없습니다.
- [ ] `Book`은 `@Serializable`(kotlinx) **그리고** `serialVersionUID`을 사용하여 `java.io.Serializable`를 구현합니다.
- [ ] §8에 나열된 모든 `companion object`은 `KLoggingChannel`을 사용합니다.
- [ ] `src/main`에 `runBlocking`이 없습니다. 아니요 `@Synchronized` / `synchronized {}`.
- [ ] `StatusPages`은 `DomainError.NotFound` -> 404, `DomainError.Conflict` -> 409, `IllegalArgumentException` -> 400, Ktor `BadRequestException` -> 400을 매핑하며 내부 세부 정보를 노출하지 않고 500을 반환하는 포괄적인 `exception<Throwable>` 핸들러가 있습니다.
- [ ] `repository/`, `service/`, `json/`, `domain/`의 공개 유형에는 중요 계약에 대한 `## Behavior / Contract` 섹션이 있는 영어 KDoc이 있습니다.

### 13.3 엔드포인트

- [ ] §7의 6개 엔드포인트는 모두 지정된 대로 응답합니다.
- [ ] `/books/stream`은(는 `/`가 아님) 정확히 `/books/stream`에 도달할 수 있습니다. 구현에는 명시적 경로가 있는 `sse("/books/stream") { }`이 사용됩니다.
- [ ] `/books/stream`은 활성 구독을 따르는 각 `POST /books`에 대해 하나 이상의 이벤트를 내보냅니다(`BufferOverflow.SUSPEND` + 5초 내보내기 시간 제한 전략으로 보장).
- [ ] `/books/export`은 한 줄에 하나의 JSON 객체가 있는 `application/x-ndjson`을 반환합니다.

### 13.4 테스트

- [ ] `./gradlew :ktor-rest-coroutines:test`은 녹색입니다.
- [ ] Route/integration 테스트에서는 `@Test fun foo() = testApplication { ... }`을 직접 사용합니다(NOT을 `runSuspendTest`으로 묶음).
- [ ] 순수한 service/repository 정지 테스트는 `runSuspendTest { }`(`testApplication` 내부 NOT)을 사용합니다.
- [ ] `BookStreamTest`은 `createClient { install(SSE) }`(`client.config { install(SSE) }` 아님)을 사용하고 §11의 백그라운드 구독 + 채널 패턴을 따릅니다.
- [ ] `BookStreamTest`은 POST *전에* SSE 구독을 시작하고, 이벤트를 버퍼링된 `Channel`에 수집하고, 게시된 책과 일치하는 하나 이상의 이벤트가 5초 이내에 도착한다고 검증문합니다(`withTimeoutOrNull(5_000)`).
- [ ] 모든 검증문은 `bluetape4k-assertions` 매처를 사용합니다.
- [ ] `assertThrows`, `kotlin.test.assertFailsWith` 또는 `invoking { } shouldThrow`를 사용하는 테스트는 없습니다.
- [ ] 최소한: `BookRoutesTest`, `HealthRoutesTest`, `BookStreamTest`가 존재하고 §11에 나열된 경우를 다룹니다.

### 13.5 문서와 공백

- [ ] 모듈 README는 (`./gradlew :ktor-rest-coroutines:run`) 실행 방법을 문서화하고 컬 예제와 함께 모든 엔드포인트를 나열합니다.
- [ ] README는 Jackson 3 대 Jackson 2 입장을 명시적으로 기록합니다(Jackson 3만 사용됩니다. Ktor JSON는 `kotlinx-serialization`입니다).
- [ ] §9 격차는 이 사양을 참조하는 `bluetape4k-projects`에서 후속 문제로 포착됩니다.

### 13.6 확인 명령(성공해야 함)

```bash
./gradlew :ktor-rest-coroutines:compileKotlin
./gradlew :ktor-rest-coroutines:test
./gradlew :ktor-rest-coroutines:build
./gradlew detekt
```

각 명령은 0을 종료해야 하며 `test`은(는) 실패 0으로 실행된 테스트가 0개 이상인 것으로 보고해야 합니다.

---

## 14. 공개 질문

1. 모듈은 또한 자신에 대해 `ktor-client`을 시연해야 합니까(임베디드 클라이언트를 통한 왕복 통합 테스트)? 기본값: 예, `BookRoutesTest`에서.
2. `/books/stream` 구독 시 기존 도서의 초기 재생을 보내야 합니까? 기본값: v1의 경우 no(라이브 전용), 향후 개선 사항으로 문서화하십시오.
3. `service.list()`을 먼저 구체화하는 대신 `Flow<Book>`에서 NDJSON 스트림을 천천히 내보내야 합니까? 기본값: v1의 경우 목록 우선; `BookRepository`가 실제 데이터베이스의 지원을 받게 되면 다시 방문하세요.

---

## 부록 A - 반복 로그 검토

### 1라운드 (2026-05-25)

| 리뷰어 | P0/P1 조사 결과 | P2/P3 조사 결과 |
|----------|---------------|----------------|
| 개발자 관점(Sonnet) | 2 HIGH, 4 MEDIUM | 0 |
| 보안 관점(Sonnet) | 1 HIGH, 4 MEDIUM | 2 LOW |
| Ops/SRE 관점(소네트) | 0 HIGH, 2 MEDIUM | 4 LOW |
| User/caller 관점(하이쿠) | 2 HIGH, 4 MEDIUM | 1 LOW |
| 비평가 통합(Opus) | 5개 P0 확인됨, 10개 P1 확인됨 | 여러 가지 거짓 긍정 통합 |
| **총 1라운드** | **5 P0, 10 P1** | — |

**모든 1라운드 P0 및 P1 결과가 사양에 적용되었습니다.**

적용된 변경사항:
- B1: AD-8 + §11 + §13.4 — `testApplication` 대 `runSuspendTest` 분할 문서화
- B2: §5.2 — 주입 가능한 협력자로 매개변수화된 `Application.module()`
- B3: AD-7 + §5.2 — StatusPages 포괄적인 `exception<Throwable>` + `BadRequestException` 추가됨
- B4: §5.5 — `BookRepository.stream()` 비차단 계약이 있는 KDoc
- B5: §5.9 + §6.4 — `call.respondBytesWriter { }` 패턴이 명시적으로 지정됨
- B6: §5.10 + §6.4 — `withContext(Dispatchers.IO)`이 `ByteWriteChannel` 쓰기에서 제거됨
- B7: §5.2 + §6.3 — 공유 `AppJson` 인스턴스 정의
- B8: §5.6 + §13.3 — `BufferOverflow.SUSPEND` (사용자 승인)
- B9: §5.9 + §6.3 — `CancellationException` SSE 수집에서 계약 재투척
- B10: §5.7 + §7.4 — 필드 유효성 검사 상수 + 필드별 제약 조건 테이블
- B11: §11 — SSE 테스트 전송(`client.config { install(SSE) }`) 지정됨
- B12: §5.11 — `src/test/resources/` 파일이 파일 맵에 추가됨
- B13: §10.0 — 문서화된 종속성 선언 정책(libs.versions.toml 이론적 근거)
- B14: §7.4 — 전체 오류 매핑 테이블 + 필드 유효성 검사 규칙
- B15: §13.2 — KDoc 체크리스트 항목 추가됨

1라운드 → 모든 B1-B15이 올바르게 적용되었습니다(2라운드에서 확인됨).

---

### 2라운드 (2026-05-25)

| 리뷰어 | CRITICAL | HIGH | MEDIUM | LOW |
|----------|---------|------|--------|-----|
| 개발자 관점(Sonnet) | 1(N1) | 1(N2) | 1(N3) | 0 |
| 보안 관점(Sonnet) | 0 | 2 (S1, S2) | 2 | 0 |
| Ops/SRE 관점(소네트) | 0 | 2(OPS-1, OPS-2) | 2 | 2 |
| User/caller 관점(하이쿠) | 0 | 2(UX-1, UX-2) | 2 | 1 |
| **총 2라운드** | **1** | **7** | **7** | **3** |

**모든 2차 결과가 적용됨:**
- N1 (CRITICAL): §5.9 + §6.3 — `sse("/books/stream") { }` 명시적 경로 포함
- N2 (HIGH): §5.2 — 500개 본문 추가 `"type" to "Internal"`
- N3 (MEDIUM): §5.5 KDoc — "cold Flow"가 "hot [MutableSharedFlow]"로 수정됨
- S1+OPS-2 (HIGH, 병합): §5.6 — `withTimeoutOrNull(5_000)` 지연 완화 + §12 생산 격차 표
- S2 (HIGH): §12 - 요청 본문 크기 제한이 문서화되지 않음
- OPS-1 (HIGH): §12 — 문서화된 정상적인 종료 없음
- OPS-3+UX-4(MEDIUM, 병합): §11 — BookStreamTest 패턴에 지정된 구체적인 5초 시간 초과 값
- OPS-4 (MEDIUM): §12 — SSE 연결 유지 하트비트가 문서화됨
- UX-1 (HIGH): §5.2 — `AppJson` 가시성 `internal val` + 문서화된 가져오기 패턴
- UX-2 (HIGH): §11 — 구체적인 BookStreamTest 백그라운드 구독 + 채널 패턴 추가됨
- UX-3 (MEDIUM): §10.3 — `testImplementation(libs.ktor.client.core)` 추가됨

2라운드 → 모든 2라운드 결과가 올바르게 적용되었습니다(3라운드에서 확인됨).

---

### 3차(2026-05-25) — 피해지역 집중 검토

| 리뷰어 | CRITICAL | HIGH | MEDIUM | LOW |
|----------|---------|------|--------|-----|
| 집중 수렴 검사(Sonnet) | 0 | 1(R3-H1) | 0 | 0 |
| **총 3라운드** | **0** | **1** | **0** | **0** |

**3차 조사 결과 적용됨:**
- R3-H1 (HIGH): §11 BookStreamTest 패턴 — `launch {}`이 `backgroundScope.launch {}`로 대체됨(testApplication 블록은 CoroutineScope이 아님); 키 불변성에 대한 설명이 추가되었습니다.

3라운드 → 1번 HIGH (R3-H1) 적용. 4차 무작위 점검에서 R3-H1 수정으로 새로운 HIGH이 도입되었습니다(R4-H1: `backgroundScope`는 testApplication 블록에서 액세스할 수 없음).

---

### 4라운드(2026-05-25) — R3-H1 수정 사항 무작위 점검

| 리뷰어 | CRITICAL | HIGH | MEDIUM | LOW |
|----------|---------|------|--------|-----|
| 즉석 점검(Sonnet) | 0 | 1(R4-H1) | 0 | 0 |
| **총 4라운드** | **0** | **1** | **0** | **0** |

**4라운드 결과 적용됨:**
- R4-H1 (HIGH): §11 BookStreamTest 패턴 — `backgroundScope.launch {}`이 `CoroutineScope(coroutineContext + SupervisorJob()).launch {}`로 대체됨; `subscriptionScope.cancel()`가 정리에 추가되었습니다. 주요 불변성이 업데이트되었습니다.

4 라운드 → 수정 후 CRITICAL = 0, HIGH = 0. 5차 현장 점검이 확정되었습니다.

---

### 5라운드(2026-05-25) — R4-H1 수정 사항에 대한 최종 무작위 점검

| 리뷰어 | CRITICAL | HIGH | MEDIUM | LOW |
|----------|---------|------|--------|-----|
| 즉석 점검(Sonnet) | 0 | 0 | 0 | 1(중복 구독.취소 - 무해) |

**5라운드 → CRITICAL = 0, HIGH = 0. ✅ CONVERGENCE CONFIRMED.**

모든 검토자(4× 다중 관점 + 6 계층 + 비평가 + 5번의 반복 라운드): CRITICAL = 0, HIGH = 3~5 라운드에서 0.

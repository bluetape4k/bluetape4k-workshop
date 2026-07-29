# 구현 계획: ktor-rest-coroutines 워크샵 모듈

- **날짜**: 2026-05-25
- **지점**: feat/ktor-rest-coroutines
- **사양**: `docs/superpowers/specs/2026-05-25-ktor-rest-coroutines-design.md`
- **모듈 경로**: `ktor/rest-coroutines`
- **Gradle 프로젝트**: `:ktor-rest-coroutines`
- **기본 패키지**: `io.bluetape4k.workshop.ktor`

---

## 단계 개요

| 단계 | 범위 | 작업 |
|---|---|---|
| 1 | 스캐폴딩 빌드(설정, 버전 카탈로그, 모듈 빌드) | T1–T3 |
| 2 | 도메인 유형(도서, DomainError) | T4-T5 |
| 3 | 저장소 계층(인터페이스 + SharedFlow 포함 인메모리) | T6-T7 |
| 4 | 서비스 계층(BookService + 유효성 검사 상수) | T8 |
| 5 | 애플리케이션 배선(ApplicationModule + AppJson + StatusPages) | T9 |
| 6 | 경로(HealthRoutes, BookRoutes, SSE, NDJSON) | T10-T12 |
| 7 | 진입점(Main.kt) 및 생산 로깅 | T13–T14 |
| 8 | 테스트 리소스(junit-platform.properties, logback-test.xml) | T15 |
| 9 | 테스트(AbstractKtorTest, 상태, 서적, 스트림, 저장소) | T16-T20 |
| 10 | 문서(README en/ko) | T21-T22 |
| 11 | 최종 검증(설정 + 카탈로그 + build/test) | T23-T24 |

---

## 1단계 — 비계 구축

### T1 — `gradle/libs.versions.toml`에 Ktor 좌표 추가
- **복잡성**: 낮음
- **파일**: `gradle/libs.versions.toml`
- **변경사항**:
  - `[versions]`에서 `ktor = "3.4.3"`을 확인합니다(이미 2단계 준비에서 추가됨 - 확인만 가능).
  - `[libraries]` 아래에 추가:
    ```toml
    ktor-bom                        = { module = "io.ktor:ktor-bom", version.ref = "ktor" }
    ktor-server-core                = { module = "io.ktor:ktor-server-core" }
    ktor-server-netty               = { module = "io.ktor:ktor-server-netty" }
    ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation" }
    ktor-server-call-logging        = { module = "io.ktor:ktor-server-call-logging" }
    ktor-server-status-pages        = { module = "io.ktor:ktor-server-status-pages" }
    ktor-server-sse                 = { module = "io.ktor:ktor-server-sse" }
    ktor-server-test-host           = { module = "io.ktor:ktor-server-test-host" }
    ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json" }
    ktor-client-core                = { module = "io.ktor:ktor-client-core" }
    ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation" }
    ```
  - `kotlinx-serialization-json` 별칭과 `kotlin-serialization` 플러그인 별칭이 이미 존재합니다. 재사용하세요. 다시 선언하지 마세요.
- **참고**: 버전은 `ktor-bom`을 통해 확인됩니다. 개별 라이브러리에는 version.ref가 필요하지 않습니다.

### T2 — `settings.gradle.kts`에 모듈 등록
- **복잡성**: 낮음
- **파일**: `settings.gradle.kts`
- **변경**: 올바른 알파벳 위치에 추가합니다.
  ```kotlin
  includeModules("ktor", false, true)
  ```
- **확인**: `./gradlew projects | grep ktor-rest-coroutines`

### T3 — 모듈 `build.gradle.kts`
- **복잡성**: 중간
- **파일**: `ktor/rest-coroutines/build.gradle.kts`
- **콘텐츠**:
  ```kotlin
  plugins {
      alias(libs.plugins.kotlin.serialization)
      application   // required for ./gradlew :ktor-rest-coroutines:run
  }

  application {
      mainClass.set("io.bluetape4k.workshop.ktor.MainKt")
  }

  configurations {
      testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
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
      implementation(libs.kotlinx.serialization.json)

      // Use libs.* catalog aliases (NOT Libs.*) — no Libs.kt exists in this workspace
      implementation(libs.bluetape4k.core)
      implementation(libs.bluetape4k.logging)
      implementation(libs.bluetape4k.coroutines)
      implementation(libs.bluetape4k.jackson3)
      implementation(libs.jackson3.module.kotlin)   // required for Jackson 3 Kotlin data class serialization

      testImplementation(libs.bluetape4k.junit5)
      testImplementation(libs.bluetape4k.assertions)
      testImplementation(libs.kotlinx.coroutines.test.lib)
      testImplementation(libs.ktor.server.test.host)
      testImplementation(libs.ktor.client.core)
      testImplementation(libs.ktor.client.content.negotiation)
  }
  ```
- **MUST NOT 포함**: `ktor-serialization-jackson`, 모든 `com.fasterxml.jackson.*`, `springBoot { }` 블록.
- **⚠ NOTE**: 이 작업공간은 `libs.*` 카탈로그 별칭 NOT `Libs.*`을 사용합니다. 이 프로젝트에는 `Libs.kt` / `buildSrc` 개체가 없습니다.
- **확인**: `./gradlew :ktor-rest-coroutines:dependencies --configuration runtimeClasspath | grep "com.fasterxml.jackson"` → 일치하는 항목이 0개일 것으로 예상됩니다.

---

## 2단계 - 도메인 레이어

### T4 — `domain/Book.kt`
- **복잡성**: 낮음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/domain/Book.kt`
- **콘텐츠**:
  ```kotlin
  package io.bluetape4k.workshop.ktor.domain

  import kotlinx.serialization.Serializable

  /**
   * Represents a book in the catalog.
   *
   * ## Behavior / Contract
   * - `@Serializable` (kotlinx) enables JSON serialization via ContentNegotiation and SSE encoding.
   * - Implements `java.io.Serializable` (workspace convention) independently of kotlinx serialization.
   * - Pure value object; no DB annotations.
   */
  @Serializable
  data class Book(
      val id: String,
      val title: String,
      val author: String,
      val year: Int,
  ) : java.io.Serializable {
      companion object {
          private const val serialVersionUID: Long = 1L
      }
  }
  ```

### T5 — `domain/DomainError.kt`
- **복잡성**: 낮음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/domain/DomainError.kt`
- **콘텐츠**:
  ```kotlin
  package io.bluetape4k.workshop.ktor.domain

  /**
   * Domain-specific exception hierarchy.
   * StatusPages in ApplicationModule maps each subclass to an HTTP status code:
   * - [NotFound] → 404
   * - [Conflict] → 409
   */
  sealed class DomainError(message: String) : RuntimeException(message) {
      /** Thrown when a book with the given id does not exist. Maps to HTTP 404. */
      class NotFound(id: String) : DomainError("Book not found: id=$id")
      /** Thrown when a book with the same id already exists. Maps to HTTP 409. */
      class Conflict(message: String) : DomainError(message)
  }
  ```

---

## 3단계 — 리포지토리 계층

### T6 — `repository/BookRepository.kt`
- **복잡성**: 중간
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/repository/BookRepository.kt`
- **내용**: KDoc을 사용하는 사양 §5.5의 인터페이스와 정확히 같습니다. 중요 사항:
  - `stream()` KDoc: **"hot [MutableSharedFlow]"** (NOT "cold") — 라이브 전용 전달, 재생 없음.
  - `save()` KDoc: 지속성 후에 스트림으로 내보냅니다. 중복 시 `DomainError.Conflict`이 발생합니다.

### T7 — `repository/InMemoryBookRepository.kt`
- **복잡성**: 높음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/repository/InMemoryBookRepository.kt`
- **주요 구현 요구사항**:
  - `MutableSharedFlow<Book>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)`
  - `save()`은 `sharedFlow.emit(book)` 주위에 `withTimeoutOrNull(5_000)`를 사용해야 합니다.
    ```kotlin
    val emitted = withTimeoutOrNull(5_000L) { sharedFlow.emit(book) } != null
    if (!emitted) {
        log.warn { "SSE emit timed out for book ${book.id}; book saved, event dropped" }
    }
    ```
  - 책은 `ConcurrentHashMap` BEFORE 방출 시도에 기록됩니다. 내구성은 SSE와 무관합니다.
  - `stream()`은 `sharedFlow.asSharedFlow()`을 반환합니다.
  - `companion object : KLoggingChannel()`.
  - `@Synchronized` 없음, I/O 차단 없음.

---

## 4단계 - 서비스 계층

### T8 — `service/BookService.kt`
- **복잡성**: 중간
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/service/BookService.kt`
- **검증 상수**(동반):
  ```kotlin
  const val MAX_ID_LENGTH = 128
  const val MAX_TITLE_LENGTH = 500
  const val MAX_AUTHOR_LENGTH = 200
  val YEAR_RANGE = 1..3000
  ```
- `create(book: Book)`은 `repository.save(book)` 호출을 BEFORE 검증합니다. bluetape4k `requireNotBlank` 확장을 사용하세요.
- `stream()`은(는) 일시중단되지 않습니다: `fun stream(): Flow<Book> = repository.stream()`.

---

## 5단계 — 애플리케이션 배선

### T9 — `ApplicationModule.kt`
- **복잡성**: 높음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/ApplicationModule.kt`
- **CRITICAL 요구사항**:
  1. `internal val AppJson = Json { ignoreUnknownKeys = true; prettyPrint = false }` — **`internal`**, NOT `private`. `BookRoutes.kt`은 `import io.bluetape4k.workshop.ktor.AppJson`를 통해 가져옵니다.
  2. 함수 서명: `fun Application.module(repository: BookRepository = InMemoryBookRepository(), jackson3: Jackson3Support = Jackson3Support())`.
  3. **`CallLogging` 가져오기**: Ktor 3.x 경로는 `import io.ktor.server.plugins.calllogging.*`(소문자, 단일 'g')입니다. `callLogging.*`도 아니고 Ktor 2 변형도 아닙니다.
  4. StatusPages 핸들러 순서(특정 첫 번째, `Throwable` 마지막):
     - `exception<DomainError.NotFound>` → 404 + `{"error":"...","type":"NotFound"}`
     - `exception<DomainError.Conflict>` → 409 + `{"error":"...","type":"Conflict"}`
     - `exception<IllegalArgumentException>` → 400 + `{"error":"...","type":"BadRequest"}`
     - `exception<BadRequestException>` → 400 + `{"error":"...","type":"BadRequest"}`
     - **`exception<Throwable>` 포괄적** → 500 + `{"error":"Internal server error","type":"Internal"}` (MUST `"type":"Internal"` 포함 — 2라운드 N2 수정)
  5. `ContentNegotiation { json(AppJson) }` — 동일한 `AppJson` 인스턴스.
  6. 플러그인 설치 순서: `CallLogging` → `ContentNegotiation` → `SSE` → `StatusPages`.

---

## 6단계 - 경로

### T10 — `routes/HealthRoutes.kt`
- **복잡성**: 낮음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/routes/HealthRoutes.kt`
- `fun Route.healthRoutes() { get("/health") { call.respondText("OK") } }`

### T11 — `routes/BookRoutes.kt`
- **복잡성**: 높음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/routes/BookRoutes.kt`
- **CRITICAL 요구사항**:
  1. **`sse("/books/stream") { ... }` 명시적 경로** — NEVER 베어 `sse { }`(2라운드 N1 수정).
  2. **`sse("/books/stream")`은 최상위 `routing { }` 범위에 있어야 하며 NOT는 `route("/books") { }`** 내에 중첩되어야 합니다. `route("/books")` 안에 중첩하면 이중 접두사 `/books/books/stream`가 생성됩니다.
  3. `import io.bluetape4k.workshop.ktor.AppJson` — SSE 인코딩을 위한 공유 인스턴스입니다.
  4. `CancellationException` MUST는 SSE 수집 블록의 `catch (e: Exception)` 앞에 다시 던져집니다.
  5. `GET /books/export`은 `call.respondBytesWriter(contentType = ContentType("application", "x-ndjson")) { jackson3.writeNdjson(this, books) }`를 사용합니다.
  6. `private object BookRoutesLog : KLoggingChannel()`을 통한 로거(확장 기능은 동반 개체를 가질 수 없음)
- 경로 구조:
  ```kotlin
  route("/books") {
      get { call.respond(service.list()) }
      get("/{id}") { ... }
      post { ... respond(HttpStatusCode.Created, created) ... }
      get("/export") { ... }
  }
  sse("/books/stream") {
      service.stream().collect { book ->
          try {
              send(ServerSentEvent(data = AppJson.encodeToString(book)))
          } catch (e: CancellationException) { throw e }
          catch (e: Exception) { log.warn(e) { "SSE send failed for book ${book.id}" } }
      }
  }
  ```

### T12 — `json/Jackson3Support.kt`
- **복잡성**: 중간
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/json/Jackson3Support.kt`
- **CRITICAL**: `ByteWriteChannel.writeStringUtf8`은(는) 이미 비차단 일시중단 상태입니다. **NO `withContext(Dispatchers.IO)`** 래퍼.
- 모든 가져오기는 `tools.jackson.*`(Jackson 3)이어야 합니다.

---

## 7단계 — 진입점 및 로깅

### T13 — `Main.kt`
- **복잡성**: 낮음
- **파일**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/Main.kt`
- `fun main() { embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true) }`

### T14 — `src/main/resources/logback.xml`
- **복잡성**: 낮음
- 최소: 콘솔 어펜더, 루트 INFO, `io.bluetape4k.workshop` at DEBUG, `io.ktor` at INFO.
- NOT Spring Boot 스타일(`defaults.xml` 포함 없음)

---

## 8단계 — 테스트 리소스

### T15 — 테스트 리소스
- **복잡성**: 낮음
- **파일**:
  - `ktor/rest-coroutines/src/test/resources/junit-platform.properties` — 워크숍 템플릿(`templates/test/resources/junit-platform.properties`)에서 복사합니다.
  - `ktor/rest-coroutines/src/test/resources/logback-test.xml` — 이 모듈의 클래스 경로에는 NO Spring Boot이 있습니다. Spring-Boot 참조 템플릿이 실패합니다. 독립형 최소 콘텐츠 사용:
    ```xml
    <?xml version="1.0" encoding="UTF-8"?>
    <configuration scan="true">
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
          <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
      </appender>
      <root level="INFO">
        <appender-ref ref="CONSOLE" />
      </root>
      <logger name="io.bluetape4k.workshop" level="DEBUG" />
      <logger name="io.ktor" level="INFO" />
    </configuration>
    ```

---

## 9단계 - 테스트

### T16 — `AbstractKtorTest.kt`
- **복잡성**: 중간
- **파일**: `ktor/rest-coroutines/src/test/kotlin/io/bluetape4k/workshop/ktor/AbstractKtorTest.kt`
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS) abstract class AbstractKtorTest { companion object : KLoggingChannel() }` — 작업 공간 CLAUDE.md는 테스트 기본 클래스에 대해 `PER_CLASS`을 요구합니다.
- 공유 도서 설비 빌더.

### T17 — `routes/HealthRoutesTest.kt`
- **복잡성**: 낮음
- 패턴: `@Test fun foo() = testApplication { application { module() }; ... }` — NOT `runSuspendTest`.
- 어설션: bluetape4k-assertions에만 해당됩니다.

### T18 — `routes/BookRoutesTest.kt`
- **복잡성**: 높음
- **12가지 테스트 사례**:
  - `GET /books` (빈 저장소) → 200 + `[]`
  - `GET /books` (사전 시드) → 200 + 목록
  - `GET /books/{id}` (존재) → 200권 이상
  - `GET /books/{id}` (누락) → 404 + `{"type":"NotFound"}`
  - `POST /books` (유효) → 201 + 책
  - `POST /books` (중복) → 409 + `{"type":"Conflict"}`
  - `POST /books` (빈 제목) → 400 + `{"type":"BadRequest"}`
  - `POST /books` (빈 ID) → 400 + `{"type":"BadRequest"}`
  - `POST /books`(범위를 벗어난 연도) → 400 + `{"type":"BadRequest"}`
  - `GET /books/export` → 200 + `Content-Type: application/x-ndjson`; 본문을 한 줄씩 분석합니다. 각 줄이 `Book`로 역직렬화된다고 검증합니다. 줄 개수가 시드 개수와 일치하는지 확인
  - `GET /health` (SSE이 설치된 전체 모듈) → 200 + `OK` (SSE 경로가 `/health`를 섀도우하지 않는지 확인)
  - 포괄적인 500: `findAll()`에 `RuntimeException`을 던지는 `FakeRepository`을 삽입합니다. 500 + 본문에 `"type":"Internal"`이 포함되어 있고 NOT에 스택 추적 텍스트가 포함되어 있음을 검증합니다.
- 각 테스트: `@Test fun foo() = testApplication { val repository = InMemoryBookRepository(); application { module(repository = repository) }; ... }` — NOT가 `runSuspendTest`로 래핑됩니다.
- 사전 시드: `repository.save(...)`을 직접 호출 — **`testApplication` 블록은 `suspend`이므로 `save()`은 `runBlocking {}` 없이 호출 가능**.

### T19 — `routes/BookStreamTest.kt`
- **복잡성**: 높음
- **MANDATORY 패턴** (라운드 4 R4-H1 — CRITICAL 구현 제약):
  ```kotlin
  @Test
  fun `SSE stream emits event after POST`() = testApplication {
      val preseeded = InMemoryBookRepository()
      application { module(repository = preseeded) }

      val sseClient = createClient { install(SSE) }
      val events = Channel<String>(Channel.BUFFERED)

      // testApplication receiver = ApplicationTestBuilder (not CoroutineScope/TestScope).
      // Neither bare launch{} nor backgroundScope.launch{} compiles here.
      val subscriptionScope = CoroutineScope(coroutineContext + SupervisorJob())
      val subscription = subscriptionScope.launch {
          sseClient.sse("/books/stream") {
              incoming.collect { event -> event.data?.let { events.send(it) } }
          }
      }

      delay(100)  // allow subscription to register on hot SharedFlow

      val response = client.post("/books") {
          contentType(ContentType.Application.Json)
          setBody("""{"id":"test-1","title":"T","author":"A","year":2024}""")
      }
      response.status shouldBeEqualTo HttpStatusCode.Created

      val received = withTimeoutOrNull(5_000) { events.receive() }
      received.shouldNotBeNull()
      received shouldContain "test-1"

      subscription.cancel()
      subscriptionScope.cancel()
      events.close()
  }
  ```
- **NOT을 `runSuspendTest`**로 포장했습니다.

### T20 — `repository/InMemoryBookRepositoryTest.kt`
- **복잡성**: 중간
- 패턴: `@Test fun foo() = runSuspendTest { ... }` — 순수 정지 단위 테스트, NOT 내부 `testApplication`.
- 표지: 저장+findById, 중복 충돌, 스트림 방출.
- `assertFailsWith<DomainError.Conflict> { ... }` 예외 검사용입니다.
- **스트림 방출 패턴**(IS이 `TestScope`인 `runSuspendTest { }` 내부이므로 `this.launch {}`을 사용할 수 있음):
  ```kotlin
  val repo = InMemoryBookRepository()
  val received = Channel<Book>(Channel.BUFFERED)
  val job = launch { repo.stream().collect { received.send(it) } }
  delay(50)
  val book = Book("b-1", "T", "A", 2020)
  repo.save(book)
  withTimeoutOrNull(2_000) { received.receive() } shouldBeEqualTo book
  job.cancel(); received.close()
  ```
  참고: `runSuspendTest`가 `TestScope` 수신기를 제공하므로 `launch {}`은 `runSuspendTest` 내에서 사용할 수 있습니다.

### T20b — `service/BookServiceTest.kt` (필수 — 검증 매트릭스)
- **복잡성**: 중간
- `runSuspendTest` + 인라인 `FakeBookRepository` 스텁을 통한 검증 매트릭스.
- ID(공백, 너무 김), 제목(공백, 너무 김), 작성자(공백, 너무 김), 연도(< 1, > 3000)의 네 가지 필드를 모두 포함해야 합니다.
- HTTP 레이어와 관계없이 `BookService.create()` 경로에서 80% 이상의 적용 범위를 보장합니다.

---

## 10단계 - 문서화

### T21 — `README.md`
- **복잡성**: 중간
- **섹션**: 아키텍처, 기능, 6개 엔드포인트 모두에 대한 컬 예제, 구성, deps, Jackson 3 입장, 실행 지침, 프로덕션 격차(사양 §12 링크), 후속 조치 `bluetape4k-ktor` 격차.

### T22 — `README.ko.md`
- **복잡성**: 낮음
- T21의 한국어 번역입니다.

---

## 11단계 - 검증

### T23 — 설정 + 카탈로그 연기 확인
- **복잡성**: 낮음
- `./gradlew projects | grep ktor-rest-coroutines`
- `rg "com.fasterxml.jackson" ktor/rest-coroutines/src` → 일치하지 않음(소스 확인)
- `rg "io.ktor.serialization.jackson" ktor/rest-coroutines/src` → 일치하는 항목이 없습니다.
- `./gradlew :ktor-rest-coroutines:dependencies --configuration runtimeClasspath | grep "com.fasterxml.jackson"` → 0 일치(클래스 경로 전이 검사)

### T24 — 빌드, 테스트, 감지
- **복잡성**: 낮음
- 사양 §13.6의 모든 항목:
  ```bash
  ./gradlew :ktor-rest-coroutines:compileKotlin
  ./gradlew :ktor-rest-coroutines:test
  ./gradlew :ktor-rest-coroutines:build
  ./gradlew detekt
  ```
- 모두 0번 출구; 테스트 보고서 > 0개의 테스트와 0개의 실패.

### T25 — CI/nightly 연기 등록 + 스펙 경로 수정
- **복잡성**: 낮음
- **워크플로 위험 카탈로그(모듈 추가)** — 모든 새 Gradle 모듈에 필수입니다.
- `rg ":ktor-rest-coroutines:test" .github/workflows/ scripts/` — 현재 부재를 확인합니다.
- `scripts/smoke-validate.sh`의 `all-smoke` 케이스에 `:ktor-rest-coroutines:test \`을 추가합니다(순수 인메모리, Testcontainers 없음 — 월-토 스모크에 해당).
- `scripts/smoke-validate.sh`의 `stale-check`인 경우 `expected=`을 1로 계산합니다.
- `nightly-tests.yml`을 확인합니다. 누락된 경우 작업 목록에 `:ktor-rest-coroutines:test`을 추가합니다.
- **사양 경로 수정**: 사양 §13.4–§13.6 참조 `:ktor:rest-coroutines:*` — 올바르지 않습니다. `includeModules("ktor", false, true)`이 생성한 플랫 프로젝트 이름은 `:ktor-rest-coroutines`입니다. 모든 사양 확인 명령을 `:ktor-rest-coroutines:*`으로 업데이트합니다.

---

## 작업 요약

| ID | 작업 | 복잡성 |
|---|---|---|
| T1 | libs.versions.toml — Ktor 항목 | 낮음 |
| T2 | settings.gradle.kts — includeModules | 낮음 |
| T3 | build.gradle.kts | 매체 |
| T4 | domain/Book.kt | 낮음 |
| T5 | domain/DomainError.kt | 낮음 |
| T6 | repository/BookRepository.kt(인터페이스) | 매체 |
| T7 | repository/InMemoryBookRepository.kt (SharedFlow + 5초 방출 시간 초과) | **높음** |
| T8 | service/BookService.kt | 매체 |
| T9 | ApplicationModule.kt(내부 AppJson, StatusPages) | **높음** |
| T10 | routes/HealthRoutes.kt | 낮음 |
| T11 | routes/BookRoutes.kt(sse 경로, CancellationException) | **높음** |
| T12 | json/Jackson3Support.kt | 매체 |
| T13 | 메인.kt | 낮음 |
| T14 | logback.xml | 낮음 |
| T15 | 테스트 리소스 | 낮음 |
| T16 | AbstractKtorTest.kt | 매체 |
| T17 | HealthRoutesTest.kt | 낮음 |
| T18 | BookRoutesTest.kt (8건) | **높음** |
| T19 | BookStreamTest.kt (SSE + CoroutineScope 패턴) | **높음** |
| T20 | InMemoryBookRepositoryTest.kt | 매체 |
| T20b | BookServiceTest.kt (필수 — 유효성 검사 매트릭스) | 매체 |
| T21 | README.md | 매체 |
| T22 | README.ko.md | 낮음 |
| T23 | 연기 검사(소스 + 클래스 경로) | 낮음 |
| T24 | Build/test/detekt | 낮음 |
| T25 | CI/nightly 스모크 등록 + 스펙 경로 수정 | 낮음 |

**매우 복잡한 작업**: T7, T9, T11, T18, T19 — 최대한 주의하여 구현하세요. 사양 중요 사항을 참조하세요.

---

## 중요한 구현 알림

복잡도가 높은 각 파일을 코딩하기 전에 다음 제약 조건을 다시 읽어보세요.

1. **`sse("/books/stream")` — NEVER `sse { }`** (2라운드 N1).
2. **`internal val AppJson`** in ApplicationModule (2라운드 UX-1); `BookRoutes.kt`이(가) 가져옵니다.
3. **`withTimeoutOrNull(5_000)` 주위 `sharedFlow.emit(book)`** in InMemoryBookRepository.save() (2라운드 S1+OPS-2).
4. **`CoroutineScope(coroutineContext + SupervisorJob()).launch {}`** in BookStreamTest (4라운드 R4-H1); `backgroundScope.launch {}`도 아니고 `launch {}`도 아닙니다.
5. **포괄`exception<Throwable>`에는 `"type" to "Internal"`이 포함되어야 합니다**(2라운드 N2).
6. **Route/integration 테스트: `= testApplication { }` 직접 — NOT을 `runSuspendTest`** (AD-8)로 묶습니다.
7. **순수 테스트: `= runSuspendTest { }` — NOT 내부 `testApplication`** (AD-8).
8. **`com.fasterxml.jackson.*`은 어디서나 가져오지 않습니다**(사양 AC §13.2).
9. **`CancellationException` 다시 throw** SSE 수집 블록(사양 §5.9)에서 브로드캐치 전에 발생합니다.
10. **`ByteWriteChannel.writeStringUtf8` 주변에는 `withContext(Dispatchers.IO)`이 없습니다**(사양 §5.10).
11. **`src/main`에는 `runBlocking`이 없습니다. `@Synchronized`**(작업공간 정책) 없음.
12. **`Book`은 `@Serializable` AND `java.io.Serializable`를 `serialVersionUID`**(AD-9)로 구현합니다.
13. **`BufferOverflow.SUSPEND`** MutableSharedFlow(NOT `DROP_OLDEST`).
14. **어설션: bluetape4k-assertions만 해당** — `assertEquals` 없음, `assertThrows` 없음.
15. **`KLoggingChannel` 모든 로거에 대해** — 확장 기능은 파일 전용 홀더 개체를 사용합니다.

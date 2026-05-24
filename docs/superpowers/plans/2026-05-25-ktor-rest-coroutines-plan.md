# Implementation Plan: ktor-rest-coroutines Workshop Module

- **Date**: 2026-05-25
- **Branch**: feat/ktor-rest-coroutines
- **Spec**: `docs/superpowers/specs/2026-05-25-ktor-rest-coroutines-design.md`
- **Module path**: `ktor/rest-coroutines`
- **Gradle project**: `:ktor-rest-coroutines`
- **Base package**: `io.bluetape4k.workshop.ktor`

---

## Phase Overview

| Phase | Scope | Tasks |
|---|---|---|
| 1 | Build scaffolding (settings, version catalog, module build) | T1–T3 |
| 2 | Domain types (Book, DomainError) | T4–T5 |
| 3 | Repository layer (interface + in-memory with SharedFlow) | T6–T7 |
| 4 | Service layer (BookService + validation constants) | T8 |
| 5 | Application wiring (ApplicationModule + AppJson + StatusPages) | T9 |
| 6 | Routes (HealthRoutes, BookRoutes, SSE, NDJSON) | T10–T12 |
| 7 | Entry point (Main.kt) and production logging | T13–T14 |
| 8 | Test resources (junit-platform.properties, logback-test.xml) | T15 |
| 9 | Tests (AbstractKtorTest, Health, Books, Stream, Repository) | T16–T20 |
| 10 | Documentation (README en/ko) | T21–T22 |
| 11 | Final verification (settings + catalog + build/test) | T23–T24 |

---

## Phase 1 — Build Scaffolding

### T1 — Add Ktor coordinates to `gradle/libs.versions.toml`
- **complexity**: low
- **file**: `gradle/libs.versions.toml`
- **changes**:
  - Confirm `ktor = "3.4.3"` under `[versions]` (already added in Step 2 prep — verify only).
  - Add under `[libraries]`:
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
  - `kotlinx-serialization-json` alias and `kotlin-serialization` plugin alias already exist — reuse, do not redeclare.
- **note**: Versions resolve via `ktor-bom`; no version.ref needed for individual libraries.

### T2 — Register module in `settings.gradle.kts`
- **complexity**: low
- **file**: `settings.gradle.kts`
- **change**: Add at the correct alphabetical position:
  ```kotlin
  includeModules("ktor", false, true)
  ```
- **verify**: `./gradlew projects | grep ktor-rest-coroutines`

### T3 — Module `build.gradle.kts`
- **complexity**: medium
- **file**: `ktor/rest-coroutines/build.gradle.kts`
- **content**:
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
- **MUST NOT include**: `ktor-serialization-jackson`, any `com.fasterxml.jackson.*`, `springBoot { }` block.
- **⚠ NOTE**: This workspace uses `libs.*` catalog aliases, NOT `Libs.*`. There is no `Libs.kt` / `buildSrc` object in this project.
- **verify**: `./gradlew :ktor-rest-coroutines:dependencies --configuration runtimeClasspath | grep "com.fasterxml.jackson"` → zero matches expected.

---

## Phase 2 — Domain Layer

### T4 — `domain/Book.kt`
- **complexity**: low
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/domain/Book.kt`
- **content**:
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
- **complexity**: low
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/domain/DomainError.kt`
- **content**:
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

## Phase 3 — Repository Layer

### T6 — `repository/BookRepository.kt`
- **complexity**: medium
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/repository/BookRepository.kt`
- **content**: Exactly the interface from spec §5.5 with KDoc. Critical points:
  - `stream()` KDoc: **"hot [MutableSharedFlow]"** (NOT "cold") — live-only delivery, no replay.
  - `save()` KDoc: emits to stream after persistence; throws `DomainError.Conflict` on duplicate.

### T7 — `repository/InMemoryBookRepository.kt`
- **complexity**: high
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/repository/InMemoryBookRepository.kt`
- **key implementation requirements**:
  - `MutableSharedFlow<Book>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)`
  - `save()` must use `withTimeoutOrNull(5_000)` around `sharedFlow.emit(book)`:
    ```kotlin
    val emitted = withTimeoutOrNull(5_000L) { sharedFlow.emit(book) } != null
    if (!emitted) {
        log.warn { "SSE emit timed out for book ${book.id}; book saved, event dropped" }
    }
    ```
  - Book is written to `ConcurrentHashMap` BEFORE the emit attempt; durability is independent of SSE.
  - `stream()` returns `sharedFlow.asSharedFlow()`.
  - `companion object : KLoggingChannel()`.
  - No `@Synchronized`, no blocking I/O.

---

## Phase 4 — Service Layer

### T8 — `service/BookService.kt`
- **complexity**: medium
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/service/BookService.kt`
- **validation constants** (in companion):
  ```kotlin
  const val MAX_ID_LENGTH = 128
  const val MAX_TITLE_LENGTH = 500
  const val MAX_AUTHOR_LENGTH = 200
  val YEAR_RANGE = 1..3000
  ```
- `create(book: Book)` validates BEFORE calling `repository.save(book)`. Use bluetape4k `requireNotBlank` extensions.
- `stream()` is non-suspend: `fun stream(): Flow<Book> = repository.stream()`.

---

## Phase 5 — Application Wiring

### T9 — `ApplicationModule.kt`
- **complexity**: high
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/ApplicationModule.kt`
- **CRITICAL requirements**:
  1. `internal val AppJson = Json { ignoreUnknownKeys = true; prettyPrint = false }` — **`internal`**, NOT `private`. `BookRoutes.kt` imports via `import io.bluetape4k.workshop.ktor.AppJson`.
  2. Function signature: `fun Application.module(repository: BookRepository = InMemoryBookRepository(), jackson3: Jackson3Support = Jackson3Support())`.
  3. **`CallLogging` import**: Ktor 3.x path is `import io.ktor.server.plugins.calllogging.*` (lowercase, single 'g'). Not `callLogging.*`, not the Ktor 2 variant.
  4. StatusPages handler order (specific first, `Throwable` last):
     - `exception<DomainError.NotFound>` → 404 + `{"error":"...","type":"NotFound"}`
     - `exception<DomainError.Conflict>` → 409 + `{"error":"...","type":"Conflict"}`
     - `exception<IllegalArgumentException>` → 400 + `{"error":"...","type":"BadRequest"}`
     - `exception<BadRequestException>` → 400 + `{"error":"...","type":"BadRequest"}`
     - **`exception<Throwable>` catch-all** → 500 + `{"error":"Internal server error","type":"Internal"}` (MUST include `"type":"Internal"` — Round 2 N2 fix)
  5. `ContentNegotiation { json(AppJson) }` — same `AppJson` instance.
  6. Plugin install order: `CallLogging` → `ContentNegotiation` → `SSE` → `StatusPages`.

---

## Phase 6 — Routes

### T10 — `routes/HealthRoutes.kt`
- **complexity**: low
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/routes/HealthRoutes.kt`
- `fun Route.healthRoutes() { get("/health") { call.respondText("OK") } }`

### T11 — `routes/BookRoutes.kt`
- **complexity**: high
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/routes/BookRoutes.kt`
- **CRITICAL requirements**:
  1. **`sse("/books/stream") { ... }` with explicit path** — NEVER bare `sse { }` (Round 2 N1 fix).
  2. **`sse("/books/stream")` must be at the top-level `routing { }` scope, NOT nested inside `route("/books") { }`**. Nesting inside `route("/books")` would produce the double-prefix `/books/books/stream`.
  3. `import io.bluetape4k.workshop.ktor.AppJson` — shared instance for SSE encoding.
  4. `CancellationException` MUST be rethrown before `catch (e: Exception)` in the SSE collect block.
  5. `GET /books/export` uses `call.respondBytesWriter(contentType = ContentType("application", "x-ndjson")) { jackson3.writeNdjson(this, books) }`.
  6. Logger via `private object BookRoutesLog : KLoggingChannel()` (extension functions cannot have companion objects).
- Route structure:
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
- **complexity**: medium
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/json/Jackson3Support.kt`
- **CRITICAL**: `ByteWriteChannel.writeStringUtf8` is already non-blocking suspend. **NO `withContext(Dispatchers.IO)`** wrapper.
- All imports must be `tools.jackson.*` (Jackson 3) only.

---

## Phase 7 — Entry Point and Logging

### T13 — `Main.kt`
- **complexity**: low
- **file**: `ktor/rest-coroutines/src/main/kotlin/io/bluetape4k/workshop/ktor/Main.kt`
- `fun main() { embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }.start(wait = true) }`

### T14 — `src/main/resources/logback.xml`
- **complexity**: low
- Minimal: Console appender, root INFO, `io.bluetape4k.workshop` at DEBUG, `io.ktor` at INFO.
- NOT Spring Boot style (no `defaults.xml` include).

---

## Phase 8 — Test Resources

### T15 — Test resources
- **complexity**: low
- **files**:
  - `ktor/rest-coroutines/src/test/resources/junit-platform.properties` — copy from workshop templates (`templates/test/resources/junit-platform.properties`).
  - `ktor/rest-coroutines/src/test/resources/logback-test.xml` — this module has NO Spring Boot on the classpath; the Spring-Boot-referencing template will fail. Use standalone minimal content:
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

## Phase 9 — Tests

### T16 — `AbstractKtorTest.kt`
- **complexity**: medium
- **file**: `ktor/rest-coroutines/src/test/kotlin/io/bluetape4k/workshop/ktor/AbstractKtorTest.kt`
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS) abstract class AbstractKtorTest { companion object : KLoggingChannel() }` — workspace CLAUDE.md mandates `PER_CLASS` on test base classes.
- Shared Book fixture builders.

### T17 — `routes/HealthRoutesTest.kt`
- **complexity**: low
- Pattern: `@Test fun foo() = testApplication { application { module() }; ... }` — NOT `runSuspendTest`.
- Assertions: bluetape4k-assertions only.

### T18 — `routes/BookRoutesTest.kt`
- **complexity**: high
- **12 test cases**:
  - `GET /books` (empty repository) → 200 + `[]`
  - `GET /books` (pre-seeded) → 200 + list
  - `GET /books/{id}` (exists) → 200 + book
  - `GET /books/{id}` (missing) → 404 + `{"type":"NotFound"}`
  - `POST /books` (valid) → 201 + book
  - `POST /books` (duplicate) → 409 + `{"type":"Conflict"}`
  - `POST /books` (blank title) → 400 + `{"type":"BadRequest"}`
  - `POST /books` (blank id) → 400 + `{"type":"BadRequest"}`
  - `POST /books` (year out of range) → 400 + `{"type":"BadRequest"}`
  - `GET /books/export` → 200 + `Content-Type: application/x-ndjson`; parse body line-by-line; assert each line deserializes to `Book`; assert line count matches seeded count
  - `GET /health` (full module with SSE installed) → 200 + `OK` (verifies SSE route does not shadow `/health`)
  - Catch-all 500: inject `FakeRepository` that throws `RuntimeException` on `findAll()`; assert 500 + body contains `"type":"Internal"` and does NOT contain stack trace text
- Each test: `@Test fun foo() = testApplication { val repository = InMemoryBookRepository(); application { module(repository = repository) }; ... }` — NOT wrapped in `runSuspendTest`.
- Pre-seed: call `repository.save(...)` directly — **`testApplication` block is `suspend`, so `save()` is callable without `runBlocking {}`**.

### T19 — `routes/BookStreamTest.kt`
- **complexity**: high
- **MANDATORY pattern** (Round 4 R4-H1 — CRITICAL implementation constraint):
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
- **NOT wrapped in `runSuspendTest`**.

### T20 — `repository/InMemoryBookRepositoryTest.kt`
- **complexity**: medium
- Pattern: `@Test fun foo() = runSuspendTest { ... }` — pure suspend unit tests, NOT inside `testApplication`.
- Cover: save+findById, duplicate conflict, stream emission.
- `assertFailsWith<DomainError.Conflict> { ... }` for exception checks.
- **Stream emission pattern** (inside `runSuspendTest { }` which IS a `TestScope`, so `this.launch {}` is available):
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
  Note: `launch {}` is available inside `runSuspendTest` because `runSuspendTest` provides a `TestScope` receiver.

### T20b — `service/BookServiceTest.kt` (required — validation matrix)
- **complexity**: medium
- Validation matrix via `runSuspendTest` + inline `FakeBookRepository` stub.
- Must cover all four fields: id (blank, too long), title (blank, too long), author (blank, too long), year (< 1, > 3000).
- Ensures 80%+ coverage on `BookService.create()` path independent of HTTP layer.

---

## Phase 10 — Documentation

### T21 — `README.md`
- **complexity**: medium
- **sections**: Architecture, features, curl examples for all 6 endpoints, config, deps, Jackson 3 stance, run instructions, production gaps (link to spec §12), follow-up `bluetape4k-ktor` gap.

### T22 — `README.ko.md`
- **complexity**: low
- Korean translation of T21.

---

## Phase 11 — Verification

### T23 — Settings + catalog smoke check
- **complexity**: low
- `./gradlew projects | grep ktor-rest-coroutines`
- `rg "com.fasterxml.jackson" ktor/rest-coroutines/src` → zero matches (source check)
- `rg "io.ktor.serialization.jackson" ktor/rest-coroutines/src` → zero matches
- `./gradlew :ktor-rest-coroutines:dependencies --configuration runtimeClasspath | grep "com.fasterxml.jackson"` → zero matches (classpath transitive check)

### T24 — Build, test, detekt
- **complexity**: low
- All of spec §13.6:
  ```bash
  ./gradlew :ktor-rest-coroutines:compileKotlin
  ./gradlew :ktor-rest-coroutines:test
  ./gradlew :ktor-rest-coroutines:build
  ./gradlew detekt
  ```
- All exit 0; test reports > 0 tests with 0 failures.

### T25 — CI/nightly smoke registration + spec path correction
- **complexity**: low
- **Workflow Hazards Catalog (Module Addition)** — mandatory for every new Gradle module.
- `rg ":ktor-rest-coroutines:test" .github/workflows/ scripts/` — confirm current absence.
- Add `:ktor-rest-coroutines:test \` to `all-smoke` case in `scripts/smoke-validate.sh` (pure in-memory, no Testcontainers — qualifies for Mon-Sat smoke).
- Bump `expected=` count by 1 in `stale-check` case of `scripts/smoke-validate.sh`.
- Check `nightly-tests.yml`; add `:ktor-rest-coroutines:test` to the task list if missing.
- **Spec path fix**: spec §13.4–§13.6 references `:ktor:rest-coroutines:*` — incorrect. The flat project name produced by `includeModules("ktor", false, true)` is `:ktor-rest-coroutines`. Update all spec verification commands to `:ktor-rest-coroutines:*`.

---

## Task Summary

| ID | Task | Complexity |
|---|---|---|
| T1 | libs.versions.toml — Ktor entries | low |
| T2 | settings.gradle.kts — includeModules | low |
| T3 | build.gradle.kts | medium |
| T4 | domain/Book.kt | low |
| T5 | domain/DomainError.kt | low |
| T6 | repository/BookRepository.kt (interface) | medium |
| T7 | repository/InMemoryBookRepository.kt (SharedFlow + 5s emit timeout) | **high** |
| T8 | service/BookService.kt | medium |
| T9 | ApplicationModule.kt (internal AppJson, StatusPages) | **high** |
| T10 | routes/HealthRoutes.kt | low |
| T11 | routes/BookRoutes.kt (sse path, CancellationException) | **high** |
| T12 | json/Jackson3Support.kt | medium |
| T13 | Main.kt | low |
| T14 | logback.xml | low |
| T15 | test resources | low |
| T16 | AbstractKtorTest.kt | medium |
| T17 | HealthRoutesTest.kt | low |
| T18 | BookRoutesTest.kt (8 cases) | **high** |
| T19 | BookStreamTest.kt (SSE + CoroutineScope pattern) | **high** |
| T20 | InMemoryBookRepositoryTest.kt | medium |
| T20b | BookServiceTest.kt (required — validation matrix) | medium |
| T21 | README.md | medium |
| T22 | README.ko.md | low |
| T23 | Smoke checks (source + classpath) | low |
| T24 | Build/test/detekt | low |
| T25 | CI/nightly smoke registration + spec path fix | low |

**High complexity tasks**: T7, T9, T11, T18, T19 — implement these with maximum care; refer to spec critical notes.

---

## Critical Implementation Reminders

Before coding each high-complexity file, re-read these constraints:

1. **`sse("/books/stream")` — NEVER `sse { }`** (Round 2 N1).
2. **`internal val AppJson`** in ApplicationModule (Round 2 UX-1); `BookRoutes.kt` imports it.
3. **`withTimeoutOrNull(5_000)` around `sharedFlow.emit(book)`** in InMemoryBookRepository.save() (Round 2 S1+OPS-2).
4. **`CoroutineScope(coroutineContext + SupervisorJob()).launch {}`** in BookStreamTest (Round 4 R4-H1); not `backgroundScope.launch {}`, not bare `launch {}`.
5. **Catch-all `exception<Throwable>` must include `"type" to "Internal"`** (Round 2 N2).
6. **Route/integration tests: `= testApplication { }` directly — NOT wrapped in `runSuspendTest`** (AD-8).
7. **Pure tests: `= runSuspendTest { }` — NOT inside `testApplication`** (AD-8).
8. **No `com.fasterxml.jackson.*` imports anywhere** (spec AC §13.2).
9. **`CancellationException` rethrown** before broad catch in SSE collect block (spec §5.9).
10. **No `withContext(Dispatchers.IO)` around `ByteWriteChannel.writeStringUtf8`** (spec §5.10).
11. **No `runBlocking` in `src/main`; no `@Synchronized`** (workspace policy).
12. **`Book` implements `@Serializable` AND `java.io.Serializable` with `serialVersionUID`** (AD-9).
13. **`BufferOverflow.SUSPEND`** on MutableSharedFlow (NOT `DROP_OLDEST`).
14. **Assertions: bluetape4k-assertions only** — no `assertEquals`, no `assertThrows`.
15. **`KLoggingChannel` for every logger** — extension functions use a file-private holder object.

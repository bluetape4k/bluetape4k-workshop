# Lessons: ktor-rest-coroutines workshop module

**Date**: 2026-05-25
**Branch**: feat/ktor-rest-coroutines
**Issue**: #14 — Ktor 기반 Kotlin-first workshop 예제 추가

---

## Root Cause / Background

이 모듈은 신규 구현이므로 버그 수정이 아닌, 구현 과정에서 발견된 블로커 패턴들을 기록한다.

---

## Key Decisions and Outcomes

### 1. `sse("/books/stream")` — explicit path mandatory

**결정**: `sse { }` 대신 반드시 `sse("/books/stream") { }` 형태로 경로 명시.

**이유**: Ktor 3에서 bare `sse { }` 는 application root에 마운트되어 `/books/stream`이 아닌 `/`에 응답한다.

**규칙**: SSE 라우트는 항상 명시적 경로를 지정하고, `route("/books")` 블록 바깥의 top-level `routing { }` 에 등록한다 (안에 넣으면 `/books/books/stream` 이중 prefix 생성).

### 2. `KLoggingChannel` 패키지 경로

**결정**: `io.bluetape4k.logging.coroutines.KLoggingChannel` (coroutines 하위 패키지).

**실수**: `io.bluetape4k.logging.KLoggingChannel`으로 잘못 작성 → Unresolved reference 에러.

**규칙**: bluetape4k logging 임포트는 항상 실제 소스 위치를 확인 후 사용할 것:
- `KLogging` → `io.bluetape4k.logging.KLogging`
- `KLoggingChannel` → `io.bluetape4k.logging.coroutines.KLoggingChannel`

### 3. bluetape4k Logger 확장 함수는 별도 임포트 필요

**결정**: `log.debug { }`, `log.warn(e) { }` 등 람다 형식 로그는 `io.bluetape4k.logging.*` 확장 함수이므로 명시적 import 필요.

**실수**: `KLogging` 임포트만으로는 `log.warn(e) { }` 형식이 컴파일되지 않음 — SLF4J `Logger`의 Java 메서드와 타입 불일치 오류.

**규칙**:
```kotlin
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.logging.info
```

### 4. `testApplication { }` 내 `coroutineContext` 충돌

**결정**: `CoroutineScope(Dispatchers.Default + SupervisorJob())` 사용.

**이유**: Ktor 3 `ApplicationTestBuilder`에 `coroutineContext` 프로퍼티가 `String` 타입으로 선언되어 있어, Kotlin coroutines의 `kotlin.coroutines.coroutineContext` 와 이름 충돌 발생.

**규칙**: `testApplication { }` 블록 내에서 SSE 구독 scope 생성 시:
```kotlin
// BAD — coroutineContext is String in ApplicationTestBuilder
val scope = CoroutineScope(coroutineContext + SupervisorJob())

// GOOD — use Dispatchers.Default explicitly
val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

### 5. Kluent vs bluetape4k-assertions

**결정**: Kluent (`org.amshove.kluent.*`)는 이 workspace의 `build.gradle.kts` 카탈로그에 없음 → `io.bluetape4k.assertions.*` 사용.

**규칙**: workshop 모듈 테스트에서 assertion import는 반드시 `io.bluetape4k.assertions.*`를 사용.

### 6. Jackson 3 NDJSON + kotlinx-serialization 공존

**결정**: ContentNegotiation은 `kotlinx-serialization-json`, NDJSON export만 `tools.jackson.*` (Jackson 3).

**검증**: `rg "com.fasterxml.jackson" ktor/rest-coroutines/src` → 0 matches 확인 필수.

### 7. `withTimeoutOrNull(5_000)` around `sharedFlow.emit()`

**결정**: SSE 구독자가 느릴 때 HTTP handler가 블로킹되지 않도록 emit에 타임아웃 추가.

**패턴**:
```kotlin
val emitted = withTimeoutOrNull(EMIT_TIMEOUT_MS) { sharedFlow.emit(book) } != null
if (!emitted) {
    log.warn { "SSE emit timed out; event dropped" }
}
```
Book은 emit 전에 저장되므로 타임아웃 시에도 저장은 보장됨.

### 8. StatusPages catch-all에 `"type":"Internal"` 필수

**결정**: `exception<Throwable>` 핸들러는 반드시 `"type" to "Internal"` 포함.

**이유**: 클라이언트가 에러 타입을 구분할 수 있어야 하고, 500 응답에서 stack trace가 노출되지 않아야 함.

---

## Review Misses (Step 2-R / 3-R 에서 발견된 것들)

- Round 2 N1: SSE explicit path (`sse("/books/stream")` vs `sse {}`)
- Round 2 N2: catch-all Throwable handler에 `"type"` 필드 누락
- Round 2 UX-1: `AppJson` visibility — `private` → `internal`
- Round 4 R4-H1: `backgroundScope.launch {}` 미접근 → `CoroutineScope(coroutineContext + SupervisorJob())` → 실제로는 `Dispatchers.Default + SupervisorJob()` 필요

---

## Future Guidance

- Ktor 3 모듈 신규 생성 시 이 모듈의 `build.gradle.kts`, `ApplicationModule.kt`, `BookRoutes.kt`를 템플릿으로 참고.
- SSE + SharedFlow 패턴은 `InMemoryBookRepository` + `BookRoutes.kt` 조합 참고.
- NDJSON export는 `Jackson3Support.kt` + `respondBytesWriter` 패턴 참고.
- bluetape4k logging 사용 시 패키지 경로와 확장 함수 임포트 항상 확인.

---

## Verification Evidence

```
모듈: :ktor-rest-coroutines
소스 컴파일: BUILD SUCCESSFUL
테스트 컴파일: BUILD SUCCESSFUL

InMemoryBookRepositoryTest : 10 tests, 0 failures
BookRoutesTest             : 12 tests, 0 failures
BookStreamTest             :  2 tests, 0 failures
HealthRoutesTest           :  1 tests, 0 failures
BookServiceTest            : 16 tests, 0 failures
총                          : 41 tests, 0 failures

./gradlew :ktor-rest-coroutines:build  → BUILD SUCCESSFUL
```

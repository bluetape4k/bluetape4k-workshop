# Coroutines Examples

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Coroutines Examples** as a runnable Kotlin language and coroutine patterns workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

![Coroutines Examples Graphviz architecture diagram](../../docs/images/readme-diagrams/kotlin-coroutines-readme-architecture-01.png)

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.kotlin` as the source of truth when comparing this README with the code.

![Coroutines Examples architecture diagram](../../docs/images/readme-diagrams/kotlin-coroutines-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `kotlin-coroutines`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Kotlin Coroutines의 핵심 개념을 학습하는 예제 모음입니다.

## 코루틴 구조 개요

![coroutines Architecture diagram](../../docs/images/readme-diagrams/kotlin-coroutines-diagram-01.png)

## 예제 범주

### 기초 (`guide/`)
| 파일 | 내용 |
|---|---|
| `CoroutineBuilderExamples` | `launch`, `async`, `runBlocking` 빌더 사용법 |
| `CoroutineContextExamples` | `CoroutineContext`, `Dispatcher` 이해 |
| `SuspendExamples` | suspend 함수 작성 패턴 |
| `FlowExamples` | Cold Stream인 `Flow` 기본 연산자 |
| `SharedFlowExamples` | Hot Stream인 `SharedFlow` / `StateFlow` |
| `ChannelExamples` | `Channel`을 이용한 Producer-Consumer 패턴 |
| `ChannelAsFlowExamples` | Channel을 Flow로 변환하는 패턴 |
| `MDCContextExamples` | 로그 MDC와 Coroutine Context 연동 |

### 취소 처리 (`cancellation/`)
- `CancellationExamples` — 코루틴 취소 전파, `CancellationException` 처리, `NonCancellable` 활용

### 커스텀 CoroutineContext (`context/`)
- `CounterCoroutineContext` — 상태를 가진 커스텀 Context Element
- `UuidProviderCoroutineContext` — 요청별 UUID 제공 Context

### 빌더 심화 (`builders/`)
- `CoroutineBuilderExamples` — `supervisorScope`, `coroutineScope`, 에러 전파 차이
- `CoroutineContextBuilderExamples` — `withContext`, Context 전환 패턴

### 스코프 관리 (`scope/`)
- `CoroutineScopeExamples` — 구조화된 동시성(Structured Concurrency)
- `SpringCoroutineScopeTest` — Spring Bean 수명주기와 CoroutineScope 연동

### Flow 테스트 (`tests/`)
- `TurbineExamples` — [Turbine](https://github.com/cashapp/turbine) 라이브러리를 사용한 Flow 테스트

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `KLoggingChannel` | `bluetape4k-logging` | 모든 companion object | 코루틴 컨텍스트를 포함한 구조적 로깅; SLF4J MDC와 연동 |
| `suspendLogging { }` | `bluetape4k-logging` | `DispatcherExamples` | suspend 컨텍스트에서 안전하게 로그 메시지 빌드 |
| `coroutines.support.log` | `bluetape4k-coroutines` | `DispatcherExamples` | Job에 이름 태그 달아 완료 로그 자동 출력 |
| `Flow<T>.log()` | `bluetape4k-coroutines` | `FlowBuilderExamples`, `FlowLifecycleExamples` | Flow 파이프라인 중간에 emit 값 로깅 |
| `coroutines.tests.assertResult` | `bluetape4k-coroutines` | `FlowBuilderExamples`, `CallbackFlowExamples` | Flow 결과를 Turbine 없이 검증하는 테스트 유틸 |
| `PropertyCoroutineContext` | `bluetape4k-coroutines` | `context/` 패키지 | 타입 안전 키-값 저장소를 가진 커스텀 CoroutineContext 구현 |
| `runSuspendTest { }` | `bluetape4k-junit5` | 테스트 전체 | JUnit 5에서 suspend 테스트를 실행하는 확장 함수 |
| `Fakers` | `bluetape4k-junit5` | 테스트 픽스처 | JavaFaker 기반 테스트 데이터 생성 유틸리티 |
| `OutputCapture` / `OutputCapturer` | `bluetape4k-junit5` | 출력 검증 테스트 | stdout/stderr 캡처 JUnit 5 확장 |
| `bluetape4k-assertions` | `bluetape4k-core` | 테스트 전체 | Kluent 스타일의 가독성 높은 단언문 (`shouldBeEqualTo`, `shouldNotBeNull` 등) |
| `Uuid` (idgenerators) | `bluetape4k-idgenerators` | `UuidProviderCoroutineContext` | UUID v7 등 다양한 ID 생성 전략 |
| `withLoggingContext { }` | `bluetape4k-logging` | MDC 연동 예제 | Kotlin DSL 방식의 MDC context 설정 |

## bluetape4k Before / After

### `KLoggingChannel` vs 표준 Logger

```kotlin
// Before — SLF4J LoggerFactory 직접 사용
class MyClass {
    companion object {
        private val log = LoggerFactory.getLogger(MyClass::class.java)
    }
}

// After — bluetape4k KLoggingChannel (코루틴 컨텍스트 포함)
class MyClass {
    companion object: KLoggingChannel()
    // log 프로퍼티 자동 생성 + 코루틴 컨텍스트 정보 로그에 포함
}
```

### `suspendLogging { }` vs 일반 로그 호출

```kotlin
// Before — 코루틴 내 일반 로그 (스레드 정보만 포함)
launch(Dispatchers.IO) {
    log.debug("Running on thread ${Thread.currentThread().name}")
}

// After — bluetape4k suspendLogging (코루틴 이름 + 스레드 정보 포함)
launch(Dispatchers.IO) {
    suspendLogging { "Running on thread ${Thread.currentThread().name}" }
    // 출력 예: [DefaultDispatcher-worker-1 @coroutine#3] Running on thread ...
}
```

### `Flow<T>.log()` 디버깅 연산자

```kotlin
// Before — 중간 값 확인을 위한 onEach + println
flow { emit(1); emit(2) }
    .onEach { println("value: $it") }
    .collect()

// After — bluetape4k .log() 확장 함수
flow { emit(1); emit(2) }
    .log("my-flow")   // 자동으로 emit/complete/error 이벤트 로깅
    .collect()
```

### `coroutines.tests.assertResult` vs Turbine

```kotlin
// Before — Turbine 라이브러리 의존 필요
someFlow.test {
    awaitItem() shouldBeEqualTo 1
    awaitItem() shouldBeEqualTo 2
    awaitComplete()
}

// After — bluetape4k assertResult (추가 의존성 없음)
someFlow.assertResult(1, 2)
```

## 참고

- [Kotlin Coroutines 공식 가이드](https://kotlinlang.org/docs/coroutines-guide.html)
- [Kotlin Flow 공식 문서](https://kotlinlang.org/docs/flow.html)

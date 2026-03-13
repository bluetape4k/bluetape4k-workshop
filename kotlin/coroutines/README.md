# Coroutines Examples

Kotlin Coroutines의 핵심 개념을 학습하는 예제 모음입니다.

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

## 참고

- [Kotlin Coroutines 공식 가이드](https://kotlinlang.org/docs/coroutines-guide.html)
- [Kotlin Flow 공식 문서](https://kotlinlang.org/docs/flow.html)

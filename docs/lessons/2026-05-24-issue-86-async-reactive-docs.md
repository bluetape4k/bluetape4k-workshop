# Issue #86: Async/Reactive Module README Enhancement

## 개요

bluetape4k-workshop의 async/reactive 관련 6개 모듈 README에 `사용된 bluetape4k 기능` 표와
`bluetape4k Before / After` 코드 스니펫을 추가했습니다.

## 업데이트된 모듈

| 모듈 | 추가 섹션 | 핵심 bluetape4k API |
|---|---|---|
| `kotlin/coroutines` | 기능 표 + Before/After 4개 | `KLoggingChannel`, `suspendLogging`, `Flow.log()`, `assertResult` |
| `spring-boot/webflux-coroutines` | 이미 완성됨 — 변경 없음 | `Dispatchers.VT`, `Flow.async`, `Runtimex`, `KLoggingChannel` |
| `spring-data/r2dbc-coroutines` | 기능 표 + Before/After 2개 | `*Suspending` 확장 함수군, `runSuspendIO` |
| `vertx/coroutines` | 기능 표 + Before/After 2개 | `suspendHandler`, `withSuspendTestContext` |
| `vertx/vertx-sqlclient` | 기능 표 + Before/After 3개 | `withSuspendTransaction`, `testWithSuspendTransaction`, `MySQL8Server.Launcher` |
| `vertx/vertx-webclient` | 기능 표 + Before/After 2개 | `suspendHandler`, `Jackson`, `withSuspendTestContext` |

## 방법론: 실제 코드 기반 문서화

기능 표와 Before/After 스니펫은 각 모듈의 실제 import 구문을 분석하여 작성했습니다.
태스크 명세에서 제안된 API 목록(`Dispatchers.VT`, `SuspendedJobTester` 등)은 실제
`kotlin/coroutines` 모듈에 사용되지 않으므로 명세 목록이 아닌 실제 사용 API를 기재했습니다.

## 주요 발견

### `spring-data/r2dbc-coroutines`의 `*Suspending` 확장 함수군

`bluetape4k-spring-boot4-r2dbc` 아티팩트가 `R2dbcEntityOperations`에 대한
suspend 래퍼를 제공합니다. `countAllSuspending`, `selectAllSuspending`,
`findOneByIdSuspending`, `findOneByIdOrNullSuspending`, `findFirstByIdSuspending`,
`insertSuspending`, `deleteAllSuspending` 등 모든 CRUD 연산이 suspend 함수로 제공되어
Mono/Flux 변환 보일러플레이트가 완전히 제거됩니다.

### `vertx/vertx-sqlclient`의 트랜잭션 패턴

`withSuspendTransaction { conn -> }`: 트랜잭션을 열고 suspend 블록 실행 후 예외 시 자동 롤백.

`testWithSuspendTransaction(testContext, pool) { }`: 테스트용 확장으로
`VertxTestContext` 완료 처리와 트랜잭션 롤백을 결합하여 테스트 격리를 자동으로 보장합니다.

### `kotlin/coroutines`의 실제 bluetape4k 의존성

이 모듈은 `Dispatchers.VT`나 `SuspendedJobTester`를 사용하지 않습니다.
실제 사용 API: `KLoggingChannel`, `suspendLogging`, `coroutines.support.log`,
`Flow.log()`, `coroutines.tests.assertResult`, `PropertyCoroutineContext`,
`runSuspendTest`, `Fakers`, `OutputCapture`, `withLoggingContext`.

## 적용한 문서화 원칙

1. **실제 코드 기반**: import 분석으로 실제 사용 API만 기재
2. **Before/After 정직성**: Before는 표준 라이브러리 방식, After는 bluetape4k 방식으로만 비교
3. **한국어 유지**: 기존 README의 한국어 스타일 유지 (workspace CLAUDE.md 정책)
4. **중복 없음**: 이미 완성된 webflux-coroutines README는 수정하지 않음

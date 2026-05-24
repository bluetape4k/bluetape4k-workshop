# Issue #87 — Observability/Performance 모듈 README 강화 교훈

## 작업 개요

- 대상: `observability/micrometer-observation`, `observability/micrometer-tracing-coroutines`,
  `virtualthreads/spring-mvc-tomcat`, `virtualthreads/spring-webflux`
- 목표: `Used bluetape4k Features` 표 + Before/After 코드 스니펫 추가 (코드 변경 없음)

## 핵심 교훈

### 1. bluetape4k API와 workshop 로컬 헬퍼를 구분해야 한다

`observability/micrometer-observation`의 `ObservationSupport.kt`에 있는
`Observation.observe { }` 람다 확장은 **workshop 로컬 코드**이지 bluetape4k 라이브러리가 아니다.
실제 bluetape4k-micrometer API는 `withObservation` / `withObservationSuspending`이다.

README를 작성할 때 `build.gradle.kts`와 실제 import 문을 모두 확인해야 한다.

### 2. 코루틴 관찰(Observation)은 별도 DSL이 필요하다

표준 Micrometer `Observation.start().stop()`은 코루틴에서
`CancellationException`을 삼킬 위험이 있다.

```kotlin
// 위험: CancellationException 누락 가능
val obs = Observation.createNotStarted("name", registry).start()
try {
    delay(100)        // suspend point — cancellation here may not propagate correctly
} finally {
    obs.stop()
}

// 안전: bluetape4k withObservationSuspending
withObservationSuspending("name", registry) {
    delay(100)        // CancellationException 자동 처리·전파
}
```

`withObservationSuspending`은 `CancellationException`을 rethrow하므로
structured concurrency를 깨뜨리지 않는다.

### 3. KLoggingChannel vs KLogging 구분

| 클래스 | 사용 상황 |
|---|---|
| `KLogging` | 일반 동기 코드 (`companion object: KLogging()`) |
| `KLoggingChannel` | 코루틴 환경 (`companion object: KLoggingChannel()`) |

코루틴 코드에서 `KLogging`을 쓰면 MDC 컨텍스트가 전파되지 않을 수 있다.

### 4. `ZipkinServer.Launcher.zipkin` 싱글턴 패턴

`GenericContainer`를 직접 쓰거나 `@Testcontainers` 어노테이션을 쓰는 대신
bluetape4k Testcontainers 싱글턴 Launcher를 사용하면
테스트 간 컨테이너를 재사용해 전체 테스트 시간을 단축할 수 있다.

### 5. README 섹션 추가 시 언어 일관성

기존 README가 한국어로 작성되어 있으면 새 섹션도 한국어로 유지한다.
영어 README에 새 섹션을 추가할 때만 영어를 사용한다.
(CLAUDE.md 문서 언어 정책: 공개 기여자용은 영어, 내부 엔지니어용은 한국어 허용)

## 파일 변경 요약

| 파일 | 변경 내용 |
|---|---|
| `observability/micrometer-observation/README.md` | bluetape4k 활용 표 + Before/After (logging DSL, ObservationTextPublisher) 추가 |
| `observability/micrometer-tracing-coroutines/README.md` | 7줄 → 전체 재작성: 구성 표, tracing 파이프라인 설명, bluetape4k 표, withObservation/withObservationSuspending Before/After, ZipkinServer.Launcher Before/After |
| `virtualthreads/spring-mvc-tomcat/README.md` | bluetape4k 표 + structuredTaskScopeAll/virtualFutureAll Before/After 추가 |
| `virtualthreads/spring-webflux/README.md` | bluetape4k 표 + Dispatchers.VT/uninitialized()/inheritInheritableThreadLocals Before/After 추가 |

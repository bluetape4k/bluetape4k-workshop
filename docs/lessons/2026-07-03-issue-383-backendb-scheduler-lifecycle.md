# Lessons: Issue #383 BackendB Programmatic Scheduler Lifecycle

## 배경

`BackendBController`는 programmatic Resilience4j decorator를 보여주는 learner-facing example이다.
Backend B에서는 annotation-based resilience를 의도적으로 피하고, `CompletableFuture` endpoint에
`TimeLimiter`와 `Retry`를 수동으로 연결한다.

## 결정

scheduler는 controller local로 유지하되 ownership을 명시한다.

- anonymous scheduled pool 대신 이름 있는 bluetape4k `NamedThreadFactory`를 사용한다.
- production code가 core helper를 import하므로 direct `bluetape4k-core` dependency를 선언한다.
- constructor default parameter를 통해 test가 scheduler를 inject할 수 있게 한다.
- `@PreDestroy`를 통해 scheduler를 닫는다.
- focused unit test로 shutdown contract를 검증한다.

## 결과

example은 여전히 따라가기 쉽다. programmatic decorator code는 같은
`withTimeLimiter(..., scheduler)` 및 `withRetry(..., scheduler)` flow를 보여주며, lifecycle
boundary는 이제 visible하고 verified 상태다.

## 검증

- Baseline build before work: `/tmp/issue383-baseline-build.log` — `BUILD SUCCESSFUL in 1m 17s`.
- Affected compile: `/tmp/issue383-affected-compile.log` — `BUILD SUCCESSFUL in 1s`.
- Targeted tests: `/tmp/issue383-targeted-tests-2.log` — `BUILD SUCCESSFUL in 24s`, `11 passing`.
- Full build after work: `/tmp/issue383-full-build.log` — `BUILD SUCCESSFUL in 2m 59s`.

## 향후 guard

workshop example이 executor, scheduler, dispatcher, client 또는 유사 runtime resource를 직접
생성하면 같은 file에서 lifecycle owner도 보여주어야 한다. Spring-managed bean 또는 explicit
close hook을 선호하고, 작은 lifecycle assertion을 추가해 cleanup도 pattern의 일부로 가르친다.

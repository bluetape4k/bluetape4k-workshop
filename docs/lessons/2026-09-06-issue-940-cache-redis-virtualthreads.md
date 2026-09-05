# Issue #940 cache-redis VirtualThreads executor 소유권

## Context

`TaskExecutorAdapter` 안에서 raw executor를 만들면 Spring context가 delegate의 종료 책임을 알 수 없고,
platform fallback의 재사용 thread에서는 MDC가 다음 작업으로 누출될 수 있다.

## Decision or Finding

stable `bluetape4k-dependencies:2.0.0`의 `VirtualThreads.executorService()`를 별도 Spring bean으로 등록하고
`destroyMethod = "shutdown"`을 둔다. `@Async` adapter와 Lettuce는 이 bean을 공유한다. Provider-defined
thread name을 유지하고 bean name과 `runtimeName()`을 관측 계약으로 사용한다.

## Outcome

Context close는 신규 작업을 거부하면서 기존 in-flight 작업을 interrupt하지 않는다. Spring dependency graph와
destruction callback은 Lettuce, adapter, delegate 순서를 보장하며 MDC는 성공·예외·빈 caller 경로에서 복원된다.

## Verification

독립 context lifecycle, 실제 Lettuce bean graph, 같은 worker MDC 회귀와 기존 Redis cache suite를 실행한다.
API/JDK25 artifact가 stable BOM의 `2.0.0`으로 resolve되는지도 별도로 확인한다.

## Future Guidance

공통 runtime executor를 consumer가 소유할 때 adapter 내부에 숨기지 않는다. 별도 thread factory 조합으로 provider
실행 모델을 바꾸지 말고, 더 강한 drain/interrupt 정책이 필요하면 명시적 lifecycle abstraction으로 분리한다.

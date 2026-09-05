# Issue #940 cache-redis VirtualThreads executor 설계

## 목표

`spring-boot/cache-redis`의 `@Async`와 Lettuce가 공유하는 실행기를
`bluetape4k-dependencies:2.0.0`의 `VirtualThreads` API로 만들고, Spring context가
생성과 종료를 한 bean에서 소유하도록 한다.

## 결정

- `cacheRedisVirtualThreadExecutor`라는 `ExecutorService` bean을
  `destroyMethod = "shutdown"`으로 등록한다.
- `applicationTaskExecutor`는 이 bean을 `TaskExecutorAdapter`에 주입한다. 기존 bean 이름,
  `@Primary`, Lettuce 주입 경계는 유지한다.
- 공통 executor의 provider-defined thread prefix를 채택한다. 기존 `async-vt-exec-`는 호환
  계약으로 유지하지 않으며, 운영 관측은 `cacheRedisVirtualThreadExecutor` bean name과
  `VirtualThreads.runtimeName()`을 사용한다. 별도 `threadFactory` 조합으로 provider의
  executor 실행 모델을 바꾸지 않는다.
- runtime 이름은 특정 JDK provider로 고정하지 않는다. `jdk25` provider가 없으면
  `platform-fallback`도 같은 lifecycle 계약을 따른다.
- `shutdown()`은 admission을 닫아 새 작업을 거부하고 이미 제출된 작업은 interrupt하지 않고 완료하도록 둔다.
  Spring context close는 작업 완료를 기다리지 않고 bounded 시간 안에 반환하며, blocked 작업을 release한 뒤
  executor가 종료되는 것을 테스트한다.
- MDC decorator는 caller context를 적용하고 `finally`에서 worker의 이전 context를 복원한다.
  caller context가 없으면 task 실행 중 MDC를 clear한다.

## 검증 경계

- 독립 Spring context를 닫은 뒤 managed executor가 shutdown되고 submit이 거부된다.
- 실제 `LettuceConnectionFactory`, `applicationTaskExecutor`, managed executor에 test-only
  `DestructionAwareBeanPostProcessor`를 적용해 destruction callback 순서를 기록하고,
  dependency graph와 함께 Lettuce → adapter → delegate 순서를 고정한다.
- 같은 worker를 재사용하는 fixture에서 MDC success, task 내부 변경 후 exception, null caller,
  pre-existing worker context를 각각 검증한다.
- 기존 Redis context/cache 테스트와 stable BOM dependency resolution을 함께 확인한다.

## 제외

- 강제 `shutdownNow`, interrupt 기반 취소, 임의 drain timeout, 새로운 lifecycle wrapper는 추가하지 않는다.
- `virtualthreads/*`나 operations 모듈의 별도 executor를 함께 이전하지 않는다.
- 기존 `async-vt-exec-` thread-name prefix를 호환 목적으로 재구현하지 않는다.

# Issue #993 leader application coroutine scope 수명주기

## Context

`leader-election`과 `job-safety-lab`이 `Dispatchers.Default + SupervisorJob()` 조합과 close 로직을 각각 직접 구현하고 있었다. 두 구현 모두 application-owned scope였지만 닫힘·취소 상태와 멱등성 계약을 각 예제가 다시 책임졌다.

## Decision or Finding

각 component가 독립 `DefaultCoroutineScope` 인스턴스를 소유하도록 했다. 전역 singleton은 사용하지 않으며, `LeaderEventListenerService`는 listener 제거 뒤 scope를 닫는 순서를 유지하고 `JobSafetyAuditShutdownCoordinator`는 전체 resource 중 scope를 마지막에 닫는 순서를 유지한다.

## Outcome

두 모듈이 `bluetape4k-coroutines`의 `SupervisorJob`, 자식 취소, 멱등 close, closed/cancelled 상태 계약을 재사용한다. 기존 bean type, qualifier, `destroyMethod = ""`, `isActive` 조회와 context restart 동작은 유지된다.

## Verification

- service listener 제거와 scope 반복 close
- 서로 독립인 두 application scope와 자식 coroutine 취소
- shutdown coordinator의 scope-last 순서
- Spring context 종료 후 inactive 상태와 restart 격리
- 두 모듈 전체 테스트, detekt, dependency graph, README parity, ecosystem reuse gate

## Future Guidance

application-owned coroutine scope는 component마다 `DefaultCoroutineScope`를 생성하고 소유자가 닫는다. `GlobalCoroutineScope` 같은 전역 인스턴스는 독립 수명주기가 필요한 Spring context나 재시작 테스트에 사용하지 않는다.

# Virtual Thread Usage Rules

[English](README.md) | 한국어

이 package는 Java virtual thread의 "do and do not" rule에 집중합니다. 테스트는
platform-thread 습관과 virtual-thread-friendly alternative를 나란히 비교합니다.

## 아키텍처

![Virtual thread usage rules architecture](../../../../../../../../../../docs/images/readme-diagrams/virtualthreads-rules-src-test-kotlin-io-bluetape4k-workshop-virtualthread-part2-readme-architecture-01.png)

## Rules

| Rule | Avoid | Prefer |
|---|---|---|
| Blocking workflow | 단순 blocking step을 callback-heavy `CompletableFuture` chain으로 표현 | `newVirtualThreadPerTaskExecutor()` 위의 synchronous code |
| Thread creation | Virtual thread를 caching하거나 pooling | Thread-per-task virtual thread executor |
| Concurrency limits | Fixed thread pool을 quota mechanism처럼 사용 | 제한 대상 resource 주변의 `Semaphore` |
| Context | 넓게 상속되는 `ThreadLocal` state | `ScopedValue` 또는 명시적 bounded context |
| Exclusive access | Carrier thread pinning을 유발할 수 있는 `synchronized` block | `ReentrantLock`과 Kotlin `withLock` |

## Test Files

| File | Main lesson |
|---|---|
| `Rule2WriteBlockingSynchronousCode` | Virtual thread는 단순 blocking workflow를 다시 읽기 쉽게 만듭니다 |
| `Rule3DoNotPoolVirtualThreads` | Virtual thread는 저렴합니다. thread가 아니라 제한 대상 resource를 pool/limit합니다 |
| `Rule4UseSemaphoreInsteadOfFixedThreadPools` | Per-task virtual thread는 유지하고 concurrent access는 semaphore로 제한합니다 |
| `Rule5UseThreadLocalVariablesCarefully` | 우발적인 context inheritance를 피하고 bounded scope를 선호합니다 |
| `Rule6UseSynchronizedBlocksAndMethodsCarefully` | Virtual-thread-aware code에서는 pinning-prone synchronized section을 피합니다 |

## 테스트

```bash
./gradlew :virtualthreads-rules:test --tests '*part2*'
```

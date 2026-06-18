# Virtual Thread Usage Rules

[한국어](README.ko.md) | English

This package focuses on the "do and do not" rules for Java virtual threads. The
tests compare platform-thread habits with virtual-thread-friendly alternatives.

## Architecture

![Virtual thread usage rules architecture](../../../../../../../../../../docs/images/readme-diagrams/virtualthreads-rules-src-test-kotlin-io-bluetape4k-workshop-virtualthread-part2-readme-architecture-01.png)

## Rules

| Rule | Avoid | Prefer |
|---|---|---|
| Blocking workflow | Callback-heavy `CompletableFuture` chains for simple blocking steps | Synchronous code on `newVirtualThreadPerTaskExecutor()` |
| Thread creation | Caching or pooling virtual threads | Thread-per-task virtual thread executor |
| Concurrency limits | Fixed thread pools as a quota mechanism | `Semaphore` around the limited resource |
| Context | Broad inherited `ThreadLocal` state | `ScopedValue` or explicit bounded context |
| Exclusive access | `synchronized` blocks that can pin carrier threads | `ReentrantLock` and Kotlin `withLock` |

## Test Files

| File | Main lesson |
|---|---|
| `Rule2WriteBlockingSynchronousCode` | Virtual threads make simple blocking workflows readable again |
| `Rule3DoNotPoolVirtualThreads` | Virtual threads are cheap; pool the constrained resource, not the thread |
| `Rule4UseSemaphoreInsteadOfFixedThreadPools` | Limit concurrent access with a semaphore while keeping per-task virtual threads |
| `Rule5UseThreadLocalVariablesCarefully` | Avoid accidental context inheritance and prefer bounded scope |
| `Rule6UseSynchronizedBlocksAndMethodsCarefully` | Avoid pinning-prone synchronized sections in virtual-thread-aware code |

## Test

```bash
./gradlew :virtualthreads-rules:test --tests '*part2*'
```

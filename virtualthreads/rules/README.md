# Virtual Thread Rules

[한국어](README.ko.md) | English

This module collects executable examples for Java virtual threads and the
bluetape4k virtual-thread helpers. The tests show how to create virtual threads,
when to keep blocking code synchronous, and which platform-thread habits should
not be copied into virtual-thread code.

## Architecture

![Virtual thread rules architecture](../../docs/images/readme-diagrams/virtualthreads-rules-readme-architecture-01.png)

## Example Groups

| Group | Files | Reader question |
|---|---|---|
| Part 1: creation APIs | `Example1` to `Example5` | How do builders, factories, and per-task executors create virtual threads? |
| Part 2: usage rules | `Rule2` to `Rule6` | When should I avoid pooling, fixed pools, `ThreadLocal`, and `synchronized`? |
| Part 3: Kotlin integration | `CoroutineWithVirtualThread`, `StructuredConcurrencyExamples` | How do virtual threads work with coroutine dispatchers and structured task scopes? |

## Key Rules

- Prefer `Executors.newVirtualThreadPerTaskExecutor()` over pooling virtual threads.
- Use `Semaphore` to limit concurrency instead of a fixed virtual-thread pool.
- Prefer scoped values or explicit context over broad `ThreadLocal` inheritance.
- Prefer `ReentrantLock`/`withLock` over `synchronized` in virtual-thread-aware code.
- Use structured task scopes when a result depends on multiple forked subtasks.

## Test

```bash
./gradlew :virtualthreads-rules:test
```

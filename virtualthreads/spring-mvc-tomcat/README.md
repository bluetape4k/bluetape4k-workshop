# Spring Boot MVC + Virtual Thread + Embedded Tomcat 예제

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **Spring Boot MVC + Virtual Thread + Embedded Tomcat 예제** as a runnable virtual-thread execution workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Architecture Diagram

The module is organized around the sample entry point or test fixture, the bluetape4k extension layer, and the runtime dependency used by the example. Keep the package under `io.bluetape4k.workshop.virtualthreads` as the source of truth when comparing this README with the code.

![Spring Boot MVC + Virtual Thread + Embedded Tomcat 예제 architecture diagram](../../docs/images/readme-diagrams/virtualthreads-spring-mvc-tomcat-diagram-01.png)

## Flow Diagram

1. Prepare the local runtime required by `virtualthreads-spring-mvc-tomcat`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Spring Boot MVC 에서 Virtual Thread 를 사용하는 예제입니다.

## Virtual Thread Processing Model

```mermaid
flowchart TD
    Client -->|HTTP Request| Tomcat
    Tomcat -->|"Virtual Thread\n(per request)"| Controller["Spring MVC Controller"]
    Controller -->|@Async| AsyncExecutor["AsyncTaskExecutor\n(Virtual Thread per task)"]
    Controller -->|structuredTaskScopeAll| VT_Pool["StructuredTaskScope\n(Virtual Threads)"]
    Controller -->|virtualFutureAll| CF_Pool["CompletableFuture\n(Virtual Threads)"]
    VT_Pool --> DB[(MySQL / JPA)]
    CF_Pool --> DB
    AsyncExecutor --> DB

    subgraph "bluetape4k-virtualthread-api"
        structuredTaskScopeAll["structuredTaskScopeAll {}"]
        virtualFutureAll["virtualFutureAll {}"]
    end
```

![Virtual Thread diagram](../../docs/images/readme-diagrams/virtualthreads-spring-mvc-tomcat-diagram-01.png)

## Environment Setup

[Kotlin + Spring Boot, Virtual Thread 적용하기](https://jsonobject.tistory.com/631) 를 참고하여 JDK 25를 설치한다. 현 예제는 JDK 25 를 사용합니다.

### Spring Boot Configuration

```yaml
spring:
    threads:
        virtual:
            enabled: true   # Enable Virtual Thread support
```

### Tomcat Virtual Thread Executor

```kotlin
/**
 * Configure Tomcat ProtocolHandler to use a Virtual Thread executor
 */
@Configuration
class TomcatConfig {

    @Bean
    fun protocolHandlerVirtualThreadExecutorCustomizer(): TomcatProtocolHandlerCustomizer<*> {
        return TomcatProtocolHandlerCustomizer<ProtocolHandler> { protocolHandler ->
            protocolHandler.executor = Executors.newVirtualThreadPerTaskExecutor()
        }
    }
}
```

### `@Async` with Virtual Threads

```kotlin
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    fun asyncTaskExecutor(): AsyncTaskExecutor {
        val factory = Thread.ofVirtual().name("async-vt-exec-", 0).factory()
        return TaskExecutorAdapter(Executors.newThreadPerTaskExecutor(factory)).apply {
            setTaskDecorator(LoggingTaskDecorator())
        }
    }
}
```

### Kotlin Coroutines with Virtual Thread Dispatcher

```kotlin
val Dispatchers.VirtualThread: CoroutineDispatcher
    get() = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
```

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `structuredTaskScopeAll` | `bluetape4k-virtualthread-api` | `VirtualThreadController.multipleTasks()` | Removes `StructuredTaskScope.ShutdownOnFailure` boilerplate; auto-aggregates exceptions |
| `virtualFutureAll` | `bluetape4k-virtualthread-api` | `VirtualThreadController.multipleTasksWithVirtualFuture()` | One-liner parallel Virtual Thread execution vs `CompletableFuture.allOf` |
| `KLoggingChannel` | `bluetape4k-logging` | `VirtualThreadController`, `AsyncConfig` | Coroutine context-aware logger companion object |
| `KLogging` | `bluetape4k-logging` | `AsyncConfig` | SLF4J companion object logger |
| Hibernate extensions | `bluetape4k-hibernate` | JPA Entity/Repository | Spring Boot 4 + Hibernate 6/7 auto-configuration |
| Cache support | `bluetape4k-cache-core` | Cache configuration | Caffeine + JCache integration |
| Testcontainers wrapper | `bluetape4k-testcontainers` | `AbstractVirtualThreadMvcTest` | MySQL container singleton — auto-start and reuse |

## Before / After

### Parallel Virtual Thread Task Execution

```kotlin
// Before — JDK StructuredTaskScope direct usage (verbose)
fun multipleTasks(): String {
    val taskSize = 100
    val factory = Thread.ofVirtual().name("vt-multi-", 0).factory()

    StructuredTaskScope.ShutdownOnFailure().use { scope ->
        repeat(taskSize) {
            scope.fork {
                Thread.sleep(Random.nextLong(500, 1000))
            }
        }
        scope.join().throwIfFailed()
    }
    return "Done $taskSize tasks"
}

// After — bluetape4k structuredTaskScopeAll
import io.bluetape4k.concurrent.virtualthread.structuredTaskScopeAll

fun multipleTasks(): String {
    val taskSize = 100
    structuredTaskScopeAll("multi", factory) { scope ->
        repeat(taskSize) {
            scope.fork {
                Thread.sleep(Random.nextLong(500, 1000))
                log.debug { "Task $it done. (${Thread.currentThread()})" }
            }
        }
        scope.join().throwIfFailed()
        Unit
    }
    return "Run multiple[$taskSize] tasks. (${Thread.currentThread()})"
}
```

### CompletableFuture-Based Parallel Virtual Thread Execution

```kotlin
// Before — manual CompletableFuture.allOf + VirtualThread executor management
fun multipleTasksWithFuture(): String {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val futures = List(100) { i ->
        CompletableFuture.runAsync({
            Thread.sleep(1000)
        }, executor)
    }
    CompletableFuture.allOf(*futures.toTypedArray()).get()
    executor.shutdown()
    return "Done"
}

// After — bluetape4k virtualFutureAll
import io.bluetape4k.concurrent.virtualthread.virtualFutureAll

fun multipleTasksWithVirtualFuture(): String {
    val tasks = List(100) {
        { Thread.sleep(1000) }
    }
    virtualFutureAll(tasks, executor).await()
    return "Run multiple[100] tasks. (${Thread.currentThread()})"
}
```

## Virtual Thread vs Platform Thread Performance Comparison

The following table summarizes observed behavior when both server configurations handle the same
Gatling load (10→400 concurrent users, 30s ramp):

| Metric | Platform Thread (default Tomcat) | Virtual Thread |
|---|---|---|
| **Thread pool limit** | ~200 threads (Tomcat default) | Unbounded (one VT per request) |
| **Blocking I/O behavior** | OS thread blocked (pool pressure) | Carrier thread released (no blocking) |
| **Memory per thread** | ~1 MB stack | ~few KB heap per VT |
| **Context switch cost** | OS kernel context switch | JVM scheduler — cheaper |
| **Throughput (blocking endpoints)** | Degrades at > 200 concurrent | Scales linearly |
| **p99 response time (400 users)** | Spikes under load | Remains stable |
| **Suitable for** | CPU-bound workloads | I/O-bound workloads (DB, HTTP, file) |

> Note: Virtual Threads do **not** improve CPU-bound workloads. Benefits appear only when threads
> spend significant time blocked on I/O (DB queries, external HTTP calls, file reads).

### When Virtual Threads Shine

```
Scenario: 400 concurrent DB queries (each blocks 50ms)

Platform threads:
    200-thread pool → 200 queries in parallel → 200 queries wait in queue
    → average response time ≈ 100ms (50ms active + 50ms queue wait)

Virtual threads:
    400 VTs scheduled on ~8 carrier threads → all 400 in progress concurrently
    → average response time ≈ 50ms (no queue wait)
```

## Load Testing with Gatling

### Step 1 — Start the Application

```bash
./gradlew :virtualthreads-spring-mvc-tomcat:bootRun
```

### Step 2 — Run Simulations

```bash
# All simulations
./gradlew :virtualthreads-spring-mvc-tomcat:gatlingRun

# Specific simulation
./gradlew :virtualthreads-spring-mvc-tomcat:gatlingRun --simulation simulations.VirtualThreadSimulation
./gradlew :virtualthreads-spring-mvc-tomcat:gatlingRun --simulation simulations.JpaSimulation
```

### Step 3 — View Reports

Reports are generated in `build/reports/gatling/<simulation-name>-<timestamp>/index.html`.

### Stop Conditions

Simulations end when the injection profile completes. To add assertion gates:

```kotlin
init {
    setUp(
        scn.injectClosed(rampConcurrentUsers(10).to(400).during(30.seconds.toJavaDuration()))
    ).protocols(httpProtocol)
     .assertions(
         global().responseTime().percentile(95.0).lt(500),   // p95 < 500ms
         global().successfulRequests().percent().gt(99.0)     // error rate < 1%
     )
}
```

## Performance Measurement

### Find Member by Id API

`/api/members/{id}` API 를 호출하여 Member 정보를 조회하는 API 입니다.

![gatling](doc/FindMemberById.png)

### JPA Find All Teams API

`/api/teams` API 를 호출하여 Team 정보를 조회하는 API 입니다.

![gatling](doc/JpaFindAllTeams.png)

## Prerequisites

- Docker (for Testcontainers MySQL)
- JDK 25
- Port 8080 available

## References

### Spring Boot with Virtual Threads

- [Kotlin + Spring Boot, Virtual Thread 적용하기](https://jsonobject.tistory.com/631)
- [A guide to using virtual threads with Spring Boot](https://bell-sw.com/blog/a-guide-to-using-virtual-threads-with-spring-boot/)
- [Virtual Threads in Springboot 3.2](https://medium.com/nerd-for-tech/virtual-threads-in-springboot-3-2-9a7250429809?)

### Gatling

- [gatling/gatling-gradle-plugin-demo-kotlin](https://github.com/gatling/gatling-gradle-plugin-demo-kotlin)
- [Stress Testing with Gatling & Kotlin - Part 2](https://medium.com/@mdportnov/stress-testing-with-gatling-kotlin-part-2-1eb13d489dc9)
- [boot-vt-benchmark](https://github.com/olegonsoftware/boot-vt-benchmark)
- [Gatling Gradle Plugin](https://docs.gatling.io/reference/extensions/build-tools/gradle-plugin/)
- [Kotlin Gatling Tutorial](https://github.com/mdportnov/kotlin-gatling-tutorial)
